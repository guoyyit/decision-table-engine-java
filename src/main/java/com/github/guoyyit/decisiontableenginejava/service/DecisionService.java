package com.github.guoyyit.decisiontableenginejava.service;

import com.github.guoyyit.decisiontableenginejava.engine.CompiledTable;
import com.github.guoyyit.decisiontableenginejava.engine.EngineSnapshot;
import com.github.guoyyit.decisiontableenginejava.engine.GrayRouter;
import com.github.guoyyit.decisiontableenginejava.engine.RuleValidator;
import com.github.guoyyit.decisiontableenginejava.engine.RuleVersionStore;
import com.github.guoyyit.decisiontableenginejava.eval.EvaluationResult;
import com.github.guoyyit.decisiontableenginejava.eval.TableEvaluator;
import com.github.guoyyit.decisiontableenginejava.model.AuditEvent;
import com.github.guoyyit.decisiontableenginejava.model.DecisionTable;
import com.github.guoyyit.decisiontableenginejava.model.GrayConfig;
import com.github.guoyyit.decisiontableenginejava.model.RuleSetVersion;
import org.springframework.stereotype.Service;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 决策服务门面。
 *
 * 并发模型：
 *  - 评估线程只持有不可变 {@link EngineSnapshot} 的引用（读路径无锁）；
 *  - 发布/激活/回滚/灰度更新在 service 内串行化，
 *    通过 store 的 CAS 提交新快照；CAS 失败重试，绝不覆盖并发提交；
 *  - 正在执行的评估始终使用进入评估时拿到的旧快照，读不到半更新状态；
 *  - 新版本提交前先做静态校验，失败则旧快照保持不变，继续提供服务。
 */
@Service
public class DecisionService {

    /** 版本内编译缓存：version -> (tableId -> CompiledTable)。旧版本缓存随其版本保留，回滚零编译成本。 */
    private final Map<Long, Map<String, CompiledTable>> compileCache = new ConcurrentHashMap<>();

    private final RuleVersionStore store;
    private final RuleValidator validator;
    private final GrayRouter grayRouter;
    private final TableEvaluator tableEvaluator;
    private final PermissionService permissions;
    private final AuditLog auditLog;

    /** 更新串行化锁（仅写路径使用，评估路径不获取）。 */
    private final Object writeLock = new Object();

    public DecisionService(RuleVersionStore store, RuleValidator validator,
                           GrayRouter grayRouter, TableEvaluator tableEvaluator,
                           PermissionService permissions, AuditLog auditLog) {
        this.store = store;
        this.validator = validator;
        this.grayRouter = grayRouter;
        this.tableEvaluator = tableEvaluator;
        this.permissions = permissions;
        this.auditLog = auditLog;
    }

    // ------------------------------------------------------------------
    // 评估路径
    // ------------------------------------------------------------------

    public EvaluationResult evaluate(String tableId, Map<String, Object> input,
                                     String stableKey, boolean forceGray,
                                     InputPolicy policy) {
        EngineSnapshot snapshot = store.snapshot();
        if (snapshot.activeVersion() < 0 && snapshot.grayConfig() == null) {
            throw new IllegalStateException("尚无已提交的规则版本，无法评估");
        }
        GrayRouter.Route route = grayRouter.route(snapshot,
                input == null ? Map.of() : input, stableKey, forceGray);

        RuleSetVersion version = snapshot.versions().get(route.version());
        if (version == null) {
            // 灰度版本丢失等极端情况：安全降级到 active 版本（快照一致性下不会发生，防御性处理）
            version = snapshot.versions().get(snapshot.activeVersion());
            route = new GrayRouter.Route(snapshot.activeVersion(), false);
        }
        DecisionTable table = findTable(version, tableId);
        if (table == null) {
            throw new IllegalArgumentException(
                    "版本 " + route.version() + " 中不存在决策表: " + tableId);
        }

        CompiledTable compiled = compileCache
                .computeIfAbsent(version.version(), v -> new ConcurrentHashMap<>())
                .computeIfAbsent(tableId, t -> CompiledTable.compile(table));

        Map<String, Object> safeInput = input == null ? Map.of() : new TreeMap<>(input);
        return tableEvaluator.evaluate(tableId, compiled, safeInput,
                version.version(), route.gray(),
                policy == InputPolicy.REJECT, Map.of());
    }

    private DecisionTable findTable(RuleSetVersion version, String tableId) {
        for (DecisionTable t : version.tables()) {
            if (t.tableId().equals(tableId)) {
                return t;
            }
        }
        return null;
    }

    // ------------------------------------------------------------------
    // 写路径
    // ------------------------------------------------------------------

    private static final int MAX_AUDIT_EVENTS = 1000;

    /**
     * 提交新版本。先做权限校验与静态校验，任何一项失败都不会改变当前快照。
     * 首次提交自动成为 active 版本；后续提交为 COMMITTED 状态，需显式 activate/灰度。
     */
    public RuleSetVersion commit(String actor, String roles, String message,
                                 List<DecisionTable> tables) {
        // 权限：先校验，越权同样记录审计
        try {
            permissions.requireAdmin(actor, roles, "COMMIT");
        } catch (PermissionService.AccessDeniedException denied) {
            recordDeniedAudit(actor, roles, "COMMIT", denied.getMessage());
            throw denied;
        }

        List<DecisionTable> copy = tables == null ? List.of() : List.copyOf(tables);
        try {
            validator.validateAll(copy);
        } catch (RuleValidator.RuleValidationException e) {
            appendAudit(actor, roles, "COMMIT",
                    "校验失败，拒绝提交: " + e.problems(), false, -1);
            throw e;
        }

        synchronized (writeLock) {
            RuleSetVersion committed;
            while (true) {
                EngineSnapshot cur = store.snapshot();
                long nextVersion = cur.versions().keySet().stream()
                        .mapToLong(Long::longValue).max().orElse(0L) + 1;
                committed = new RuleSetVersion(nextVersion, copy, message, actor,
                        System.currentTimeMillis());
                TreeMap<Long, RuleSetVersion> all = new TreeMap<>(cur.versions());
                all.put(nextVersion, committed);
                long active = cur.activeVersion() < 0 ? nextVersion : cur.activeVersion();
                EngineSnapshot next = new EngineSnapshot(
                        Map.copyOf(all), active, cur.grayConfig(),
                        withAudit(cur.auditLog(),
                                new AuditEvent(System.currentTimeMillis(), actor, roles,
                                        "COMMIT", "提交版本 " + nextVersion + ": " + message,
                                        true, nextVersion)));
                if (store.cas(cur, next)) {
                    return committed;
                }
            }
        }
    }

    /** 激活指定版本（全量发布）。 */
    public void activate(String actor, String roles, long version) {
        try {
            permissions.requireAdmin(actor, roles, "ACTIVATE");
        } catch (PermissionService.AccessDeniedException denied) {
            recordDeniedAudit(actor, roles, "ACTIVATE", denied.getMessage());
            throw denied;
        }
        synchronized (writeLock) {
            mutate("ACTIVATE", actor, roles, version, cur -> {
                if (!cur.versions().containsKey(version)) {
                    throw new IllegalArgumentException("版本不存在: " + version);
                }
                return new EngineSnapshot(cur.versions(), version, cur.grayConfig(),
                        withAudit(cur.auditLog(), new AuditEvent(System.currentTimeMillis(),
                                actor, roles, "ACTIVATE", "激活版本 " + version, true, version)));
            });
        }
    }

    /**
     * 回滚到历史版本。回滚不新建版本、不改写任何历史版本内容，
     * 只是将 active 指针指回旧版本；其行为与该版本当时完全一致
     * （旧版本编译结果仍在缓存中，且版本对象本身不可变）。
     */
    public void rollback(String actor, String roles, long version) {
        try {
            permissions.requireAdmin(actor, roles, "ROLLBACK");
        } catch (PermissionService.AccessDeniedException denied) {
            recordDeniedAudit(actor, roles, "ROLLBACK", denied.getMessage());
            throw denied;
        }
        synchronized (writeLock) {
            mutate("ROLLBACK", actor, roles, version, cur -> {
                if (!cur.versions().containsKey(version)) {
                    throw new IllegalArgumentException("回滚目标版本不存在: " + version);
                }
                // 回滚后清除灰度，避免灰度指针指向与回滚目标不一致的版本
                return new EngineSnapshot(cur.versions(), version, null,
                        withAudit(cur.auditLog(), new AuditEvent(System.currentTimeMillis(),
                                actor, roles, "ROLLBACK", "回滚到版本 " + version, true, version)));
            });
        }
    }

    /** 配置灰度。grayVersion 传 null 或 percent=0 表示关闭灰度。 */
    public void configureGray(String actor, String roles,
                              Long grayVersion, Integer percent,
                              Set<String> allowList,
                              List<String> stableKeyFields) {
        try {
            permissions.requireAdmin(actor, roles, "CONFIGURE_GRAY");
        } catch (PermissionService.AccessDeniedException denied) {
            recordDeniedAudit(actor, roles, "CONFIGURE_GRAY", denied.getMessage());
            throw denied;
        }
        int pct = percent == null ? 0 : percent;
        if (pct < 0 || pct > 100) {
            throw new IllegalArgumentException("灰度百分比必须在 [0,100]，实际: " + pct);
        }
        synchronized (writeLock) {
            mutate("CONFIGURE_GRAY", actor, roles, grayVersion == null ? -1 : grayVersion, cur -> {
                if (grayVersion != null && !cur.versions().containsKey(grayVersion)) {
                    throw new IllegalArgumentException("灰度版本不存在: " + grayVersion);
                }
                GrayConfig cfg = (grayVersion == null || pct == 0) ? null
                        : new GrayConfig(grayVersion, pct,
                                allowList == null ? Set.of() : Set.copyOf(allowList),
                                stableKeyFields == null ? List.of() : List.copyOf(stableKeyFields));
                String detail = cfg == null ? "关闭灰度"
                        : "灰度版本 " + grayVersion + " 百分比 " + pct
                                + " 白名单 " + cfg.allowList();
                return new EngineSnapshot(cur.versions(), cur.activeVersion(), cfg,
                        withAudit(cur.auditLog(), new AuditEvent(System.currentTimeMillis(),
                                actor, roles, "CONFIGURE_GRAY", detail, true,
                                grayVersion == null ? -1 : grayVersion)));
            });
        }
    }

    private interface SnapshotMutation {
        EngineSnapshot apply(EngineSnapshot cur);
    }

    /** CAS 重试提交快照变更；校验类异常向上抛出且不改变快照。 */
    private void mutate(String action, String actor, String roles, long version,
                        SnapshotMutation mutation) {
        while (true) {
            EngineSnapshot cur = store.snapshot();
            EngineSnapshot next = mutation.apply(cur);
            if (store.cas(cur, next)) {
                return;
            }
        }
    }

    private List<AuditEvent> withAudit(List<AuditEvent> base, AuditEvent event) {
        List<AuditEvent> next = auditLog.append(base, event);
        if (next.size() > MAX_AUDIT_EVENTS) {
            next = List.copyOf(next.subList(next.size() - MAX_AUDIT_EVENTS, next.size()));
        }
        return next;
    }

    private void appendAudit(String actor, String roles, String action,
                             String detail, boolean allowed, long version) {
        synchronized (writeLock) {
            mutate(action, actor, roles, version, cur -> new EngineSnapshot(
                    cur.versions(), cur.activeVersion(), cur.grayConfig(),
                    withAudit(cur.auditLog(), new AuditEvent(System.currentTimeMillis(),
                            actor, roles, action, detail, allowed, version))));
        }
    }

    /** 越权审计不能因再次越权而失败；即使写路径繁忙也尽力记录。 */
    private void recordDeniedAudit(String actor, String roles, String action, String detail) {
        try {
            appendAudit(actor, roles, action, detail, false, -1);
        } catch (RuntimeException ignore) {
            // 审计记录失败不应掩盖原始的拒绝异常
        }
    }

    // 仅供控制器/测试查询
    public EngineSnapshot currentSnapshot() {
        return store.snapshot();
    }

    /** 重置引擎（仅测试环境使用）。 */
    public void resetForTest() {
        synchronized (writeLock) {
            store.reset();
            compileCache.clear();
        }
    }
}
