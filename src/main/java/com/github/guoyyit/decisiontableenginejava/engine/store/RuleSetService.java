package com.github.guoyyit.decisiontableenginejava.engine.store;

import com.github.guoyyit.decisiontableenginejava.engine.audit.AuditService;
import com.github.guoyyit.decisiontableenginejava.engine.auth.PermissionService;
import com.github.guoyyit.decisiontableenginejava.engine.eval.CompiledRuleSet;
import com.github.guoyyit.decisiontableenginejava.engine.eval.RuleSetCompiler;
import com.github.guoyyit.decisiontableenginejava.engine.eval.RuleSetEvaluator;
import com.github.guoyyit.decisiontableenginejava.engine.model.EvaluationResult;
import com.github.guoyyit.decisiontableenginejava.engine.model.ForbiddenException;
import com.github.guoyyit.decisiontableenginejava.engine.model.PublishRequest;
import com.github.guoyyit.decisiontableenginejava.engine.model.Role;
import com.github.guoyyit.decisiontableenginejava.engine.model.RuleEngineException;
import com.github.guoyyit.decisiontableenginejava.engine.model.RuleSet;
import com.github.guoyyit.decisiontableenginejava.engine.model.RuleSetStatus;
import com.github.guoyyit.decisiontableenginejava.engine.model.RuleSetValidationException;
import com.github.guoyyit.decisiontableenginejava.engine.model.VersionView;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.NavigableMap;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicReference;

/**
 * 规则集版本服务：负责版本发布、灰度分流、回滚与原子切换。
 *
 * <h2>核心不变量</h2>
 * <ul>
 *   <li>评估只读取 {@link AtomicReference} 中的不可变预编译版本，更新期间
 *       正在执行的评估不会读到半更新状态（发布流程是"先编译后切换引用"）。</li>
 *   <li>发布校验/编译失败时不切换引用，上一个可用版本继续服务。</li>
 *   <li>灰度分流使用稳定哈希（FNV-1a 32 位），同一 key 永远路由到同一版本。</li>
 *   <li>回滚 = 复制历史版本内容生成新版本并全量激活，行为与历史版本完全一致。</li>
 * </ul>
 */
@Service
public class RuleSetService {

    private final RuleSetCompiler compiler;
    private final RuleSetEvaluator evaluator;
    private final AuditService auditService;
    private final PermissionService permissionService;

    /** 规则集名 -> (版本号 -> 规则集)，TreeMap 保证版本有序。 */
    private final Map<String, NavigableMap<Integer, RuleSet>> versions = new ConcurrentHashMap<>();
    /** 规则集名 -> (版本号 -> 预编译规则集)。 */
    private final Map<String, Map<Integer, CompiledRuleSet>> compiledCache = new ConcurrentHashMap<>();
    /** 当前全量生效版本（原子引用）。 */
    private final Map<String, AtomicReference<CompiledRuleSet>> activeRefs = new ConcurrentHashMap<>();
    /** 当前灰度版本（原子引用）。 */
    private final Map<String, AtomicReference<GrayVersion>> grayRefs = new ConcurrentHashMap<>();

    public RuleSetService(RuleSetCompiler compiler,
                          RuleSetEvaluator evaluator,
                          AuditService auditService,
                          PermissionService permissionService) {
        this.compiler = compiler;
        this.evaluator = evaluator;
        this.auditService = auditService;
        this.permissionService = permissionService;
    }

    /** 灰度版本载体。 */
    public record GrayVersion(CompiledRuleSet compiled, int ratio, String grayKey) {
    }

    /**
     * 发布一个新版本。
     *
     * @return 编译后的新版本
     * @throws ForbiddenException         无发布权限
     * @throws RuleSetValidationException 校验或编译失败（旧版本继续服务）
     */
    public synchronized CompiledRuleSet publish(PublishRequest request, String user) {
        try {
            permissionService.require(user, Role.PUBLISHER);
        } catch (ForbiddenException e) {
            auditService.denied(user, "PUBLISH", request.ruleSetName(), e.getMessage());
            throw e;
        }
        if (request.ruleSetName() == null || request.ruleSetName().isBlank()) {
            auditService.failure(user, "PUBLISH", request.ruleSetName(), "规则集名不能为空");
            throw new RuleSetValidationException("规则集名不能为空");
        }
        if (request.tables() == null || request.tables().isEmpty()) {
            auditService.failure(user, "PUBLISH", request.ruleSetName(), "决策表不能为空");
            throw new RuleSetValidationException("决策表不能为空");
        }
        int ratio = request.grayRatio() == null ? 100 : request.grayRatio();
        if (ratio < 0 || ratio > 100) {
            auditService.failure(user, "PUBLISH", request.ruleSetName(), "grayRatio 越界: " + ratio);
            throw new RuleSetValidationException("grayRatio 必须在 0..100 之间: " + ratio);
        }

        NavigableMap<Integer, RuleSet> versionMap =
                versions.computeIfAbsent(request.ruleSetName(), k -> new TreeMap<>());
        int newVersion = versionMap.isEmpty() ? 1 : versionMap.lastKey() + 1;
        RuleSetStatus status = ratio == 100 ? RuleSetStatus.ACTIVE
                : ratio == 0 ? RuleSetStatus.INACTIVE : RuleSetStatus.GRAY;
        RuleSet ruleSet = new RuleSet(
                request.ruleSetName(), newVersion, status, request.tables(),
                user, java.time.Instant.now(), request.comment(), ratio,
                request.grayKey() == null ? "" : request.grayKey());

        CompiledRuleSet compiled;
        try {
            compiled = compiler.compile(ruleSet);
        } catch (RuntimeException e) {
            // 编译失败：不切换任何引用，旧版本继续服务
            auditService.failure(user, "PUBLISH",
                    request.ruleSetName() + "#v" + newVersion,
                    "校验失败，已保留上一可用版本: " + e.getMessage());
            throw e;
        }

        // 编译成功后才提交：先降级旧版本，再写版本存储，最后原子切换引用
        demotePrevious(request.ruleSetName(), status);
        versionMap.put(newVersion, ruleSet);
        compiledCache.computeIfAbsent(request.ruleSetName(), k -> new ConcurrentHashMap<>())
                .put(newVersion, compiled);
        if (status == RuleSetStatus.ACTIVE) {
            activeRefs.computeIfAbsent(request.ruleSetName(), k -> new AtomicReference<>()).set(compiled);
            grayRefs.computeIfAbsent(request.ruleSetName(), k -> new AtomicReference<>()).set(null);
        } else if (status == RuleSetStatus.GRAY) {
            grayRefs.computeIfAbsent(request.ruleSetName(), k -> new AtomicReference<>())
                    .set(new GrayVersion(compiled, ratio, ruleSet.grayKey()));
        }
        auditService.success(user, "PUBLISH", request.ruleSetName() + "#v" + newVersion,
                "status=" + status + ", grayRatio=" + ratio + ", tables=" + request.tables().size());
        return compiled;
    }

    /**
     * 回滚到指定历史版本：复制该版本内容生成一个新的全量激活版本。
     * 回滚后行为与历史版本完全一致（内容逐字段相同）。
     */
    public synchronized CompiledRuleSet rollback(String name, int targetVersion, String user) {
        try {
            permissionService.require(user, Role.ADMIN);
        } catch (ForbiddenException e) {
            auditService.denied(user, "ROLLBACK", name + "#v" + targetVersion, e.getMessage());
            throw e;
        }
        NavigableMap<Integer, RuleSet> versionMap = versions.get(name);
        if (versionMap == null || !versionMap.containsKey(targetVersion)) {
            auditService.failure(user, "ROLLBACK", name + "#v" + targetVersion, "目标版本不存在");
            throw new RuleEngineException("规则集 " + name + " 不存在版本 v" + targetVersion);
        }
        RuleSet target = versionMap.get(targetVersion);
        int newVersion = versionMap.lastKey() + 1;
        RuleSet copy = target.withVersion(newVersion, RuleSetStatus.ACTIVE);
        CompiledRuleSet compiled;
        try {
            compiled = compiler.compile(copy);
        } catch (RuntimeException e) {
            auditService.failure(user, "ROLLBACK", name + "#v" + targetVersion,
                    "回滚编译失败，已保留当前版本: " + e.getMessage());
            throw e;
        }
        demotePrevious(name, RuleSetStatus.ACTIVE);
        versionMap.put(newVersion, copy);
        compiledCache.computeIfAbsent(name, k -> new ConcurrentHashMap<>()).put(newVersion, compiled);
        activeRefs.computeIfAbsent(name, k -> new AtomicReference<>()).set(compiled);
        grayRefs.computeIfAbsent(name, k -> new AtomicReference<>()).set(null);
        auditService.success(user, "ROLLBACK", name + "#v" + newVersion,
                "回滚自 v" + targetVersion + "，内容逐字段复制");
        return compiled;
    }

    /** 将灰度版本提升为全量。 */
    public synchronized CompiledRuleSet promote(String name, String user) {
        try {
            permissionService.require(user, Role.ADMIN);
        } catch (ForbiddenException e) {
            auditService.denied(user, "PROMOTE", name, e.getMessage());
            throw e;
        }
        AtomicReference<GrayVersion> grayRef = grayRefs.get(name);
        GrayVersion gray = grayRef == null ? null : grayRef.get();
        if (gray == null) {
            auditService.failure(user, "PROMOTE", name, "当前无灰度版本");
            throw new RuleEngineException("规则集 " + name + " 当前无灰度版本");
        }
        int newVersion = versions.get(name).lastKey() + 1;
        RuleSet copy = gray.compiled().ruleSet().withVersion(newVersion, RuleSetStatus.ACTIVE);
        CompiledRuleSet compiled = compiler.compile(copy);
        demotePrevious(name, RuleSetStatus.ACTIVE);
        versions.get(name).put(newVersion, copy);
        compiledCache.computeIfAbsent(name, k -> new ConcurrentHashMap<>()).put(newVersion, compiled);
        activeRefs.computeIfAbsent(name, k -> new AtomicReference<>()).set(compiled);
        grayRef.set(null);
        auditService.success(user, "PROMOTE", name + "#v" + newVersion,
                "灰度 v" + gray.compiled().version() + " 提升为全量");
        return compiled;
    }

    /**
     * 评估：快照当前激活/灰度引用后执行，更新期间不会读到半更新状态。
     *
     * @param name       规则集名
     * @param input      输入字段
     * @param routingKey 灰度分流 key；为 null 时尝试从输入字段 grayKey 取
     */
    public EvaluationResult evaluate(String name, Map<String, Object> input, String routingKey) {
        AtomicReference<CompiledRuleSet> activeRef = activeRefs.get(name);
        CompiledRuleSet active = activeRef == null ? null : activeRef.get();
        AtomicReference<GrayVersion> grayRef = grayRefs.get(name);
        GrayVersion gray = grayRef == null ? null : grayRef.get();

        CompiledRuleSet chosen = active;
        if (gray != null && gray.ratio() > 0) {
            String key = routingKey;
            if ((key == null || key.isBlank()) && gray.grayKey() != null && !gray.grayKey().isBlank()) {
                Object fromInput = input.get(gray.grayKey());
                key = fromInput == null ? "" : fromInput.toString();
            }
            if (key != null && !key.isBlank() && (stableHash(key) % 100) < gray.ratio()) {
                chosen = gray.compiled();
            }
        }
        if (chosen == null) {
            throw new RuleEngineException("规则集 [" + name + "] 暂无已发布的可用版本");
        }
        return evaluator.evaluate(chosen, input);
    }

    /** 列出规则集的所有版本视图（按版本号升序）。 */
    public List<VersionView> listVersions(String name) {
        NavigableMap<Integer, RuleSet> versionMap = versions.get(name);
        if (versionMap == null) {
            return List.of();
        }
        List<VersionView> views = new ArrayList<>();
        for (RuleSet rs : versionMap.values()) {
            int rowCount = rs.tables().stream().mapToInt(t -> t.rows().size()).sum();
            views.add(new VersionView(
                    rs.version(), rs.name(), rs.status(), rs.createdBy(), rs.createdAt(),
                    rs.comment(), rs.grayRatio(), rs.grayKey(), rs.tables().size(), rowCount));
        }
        return List.copyOf(views);
    }

    /** 获取指定版本的规则集定义。 */
    public RuleSet getVersion(String name, int version) {
        NavigableMap<Integer, RuleSet> versionMap = versions.get(name);
        if (versionMap == null || !versionMap.containsKey(version)) {
            throw new RuleEngineException("规则集 " + name + " 不存在版本 v" + version);
        }
        return versionMap.get(version);
    }

    /** 返回所有已注册的规则集名。 */
    public java.util.Set<String> names() {
        return java.util.Collections.unmodifiableSet(versions.keySet());
    }

    // ------------------------------------------------------------------

    /** 将旧的 ACTIVE/GRAY 版本标记为 INACTIVE（版本存储中保留不可变历史）。 */
    private void demotePrevious(String name, RuleSetStatus newStatus) {
        NavigableMap<Integer, RuleSet> versionMap = versions.get(name);
        if (versionMap == null) {
            return;
        }
        for (Map.Entry<Integer, RuleSet> e : versionMap.entrySet()) {
            RuleSet rs = e.getValue();
            if (rs.status() == RuleSetStatus.ACTIVE && newStatus == RuleSetStatus.ACTIVE) {
                versionMap.put(e.getKey(), rs.withStatus(RuleSetStatus.INACTIVE));
            } else if (rs.status() == RuleSetStatus.GRAY) {
                versionMap.put(e.getKey(), rs.withStatus(RuleSetStatus.INACTIVE));
            }
        }
    }

    /**
     * 稳定哈希（FNV-1a 32 位）：同一 key 在任何时刻、任何 JVM 下都得到相同非负整数，
     * 用于灰度分流，不依赖 {@link Object#hashCode()} 与任何随机化。
     */
    static int stableHash(String key) {
        int hash = 0x811c9dc5;
        byte[] bytes = key.getBytes(StandardCharsets.UTF_8);
        for (byte b : bytes) {
            hash ^= (b & 0xff);
            hash *= 0x01000193;
        }
        return hash & 0x7fffffff;
    }
}
