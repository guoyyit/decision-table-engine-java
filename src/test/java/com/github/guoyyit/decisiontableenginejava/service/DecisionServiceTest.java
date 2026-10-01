package com.github.guoyyit.decisiontableenginejava.service;

import com.github.guoyyit.decisiontableenginejava.engine.GrayRouter;
import com.github.guoyyit.decisiontableenginejava.engine.RuleValidator;
import com.github.guoyyit.decisiontableenginejava.engine.RuleVersionStore;
import com.github.guoyyit.decisiontableenginejava.eval.EvalStatus;
import com.github.guoyyit.decisiontableenginejava.eval.EvaluationResult;
import com.github.guoyyit.decisiontableenginejava.eval.InputValidator;
import com.github.guoyyit.decisiontableenginejava.eval.TableEvaluator;
import com.github.guoyyit.decisiontableenginejava.model.DecisionTable;
import com.github.guoyyit.decisiontableenginejava.model.HitPolicy;
import com.github.guoyyit.decisiontableenginejava.model.RuleSetVersion;
import com.github.guoyyit.decisiontableenginejava.model.TiePolicy;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static com.github.guoyyit.decisiontableenginejava.support.TestTables.*;
import static org.junit.jupiter.api.Assertions.*;

class DecisionServiceTest {

    private static final String ADMIN = "ROLE_RULE_ADMIN";
    private static final String VIEWER = "ROLE_RULE_VIEWER";

    private RuleVersionStore store;
    private DecisionService service;

    @BeforeEach
    void setUp() {
        store = new RuleVersionStore();
        service = new DecisionService(store, new RuleValidator(), new GrayRouter(),
                new TableEvaluator(new InputValidator()),
                new PermissionService(), new AuditLog(store));
    }

    private DecisionTable pricingTable(String v1Outcome, String v2Outcome) {
        return table("pricing", HitPolicy.UNIQUE, TiePolicy.CONFLICT,
                fields(str("region", true, "CN", "US")), List.of("outcome"),
                row("r1", 0, List.of(eq("region", "CN")),
                        actions("outcome", v1Outcome)),
                row("r2", 0, List.of(eq("region", "US")),
                        actions("outcome", v2Outcome)));
    }

    private DecisionService svc() {
        return service;
    }

    @Test
    void firstCommitBecomesActiveAndEvaluationUsesCommittedVersion() {
        RuleSetVersion v1 = svc().commit("alice", ADMIN, "init",
                List.of(pricingTable("CN_V1", "US_V1")));
        assertEquals(1, v1.version());
        assertEquals(1, store.snapshot().activeVersion());
        EvaluationResult r = svc().evaluate("pricing", Map.of("region", "CN"),
                null, false, InputPolicy.REJECT);
        assertEquals(EvalStatus.HIT, r.status());
        assertEquals("CN_V1", r.outputs().get("outcome"));
        assertEquals(1, r.version());
    }

    @Test
    void activateSwitchesVersionAtomicallyAndOldVersionNeverFieldMixes() {
        svc().commit("alice", ADMIN, "v1", List.of(pricingTable("CN_V1", "US_V1")));
        // v2：动作值整体替换；提交后默认仍指向 v1
        svc().commit("bob", ADMIN, "v2", List.of(pricingTable("CN_V2", "US_V2")));
        assertEquals(1, store.snapshot().activeVersion());
        assertEquals("CN_V1", svc().evaluate("pricing", Map.of("region", "CN"),
                null, false, InputPolicy.REJECT).outputs().get("outcome"));

        svc().activate("bob", ADMIN, 2);
        EvaluationResult r = svc().evaluate("pricing", Map.of("region", "US"),
                null, false, InputPolicy.REJECT);
        assertEquals(2, r.version());
        assertEquals("US_V2", r.outputs().get("outcome"));
        // 不允许字段级混合：同一版本内两列必须同为 V2
        EvaluationResult cn = svc().evaluate("pricing", Map.of("region", "CN"),
                null, false, InputPolicy.REJECT);
        assertEquals("CN_V2", cn.outputs().get("outcome"));
    }

    @Test
    void rollbackRestoresHistoricalBehaviorExactly() {
        svc().commit("alice", ADMIN, "v1", List.of(pricingTable("CN_V1", "US_V1")));
        svc().commit("bob", ADMIN, "v2", List.of(pricingTable("CN_V2", "US_V2")));
        svc().activate("bob", ADMIN, 2);
        assertEquals("CN_V2", svc().evaluate("pricing", Map.of("region", "CN"),
                null, false, InputPolicy.REJECT).outputs().get("outcome"));

        svc().rollback("alice", ADMIN, 1);
        EvaluationResult r = svc().evaluate("pricing", Map.of("region", "CN"),
                null, false, InputPolicy.REJECT);
        assertEquals(1, r.version());
        assertEquals("CN_V1", r.outputs().get("outcome"));
        assertEquals("US_V1", svc().evaluate("pricing", Map.of("region", "US"),
                null, false, InputPolicy.REJECT).outputs().get("outcome"));
        // 历史版本对象内容未被改写
        assertEquals("v1", store.snapshot().versions().get(1L).message());
    }

    @Test
    void failedCommitKeepsPreviousVersionServing() {
        svc().commit("alice", ADMIN, "v1", List.of(pricingTable("CN_V1", "US_V1")));
        // 非法表：行 ID 重复 + 表 id 相同也允许在新版本提交，但这里给重复行
        DecisionTable invalid = table("pricing", HitPolicy.UNIQUE, TiePolicy.CONFLICT,
                fields(str("region", true, "CN")), List.of("outcome"),
                row("r1", 0, List.of(eq("region", "CN")), actions("outcome", "X")),
                row("r1", 0, List.of(eq("region", "CN")), actions("outcome", "Y")));
        RuleValidator.RuleValidationException ex = assertThrows(
                RuleValidator.RuleValidationException.class,
                () -> svc().commit("bob", ADMIN, "bad", List.of(invalid)));
        assertFalse(ex.problems().isEmpty());
        // 版本号未增长，评估继续使用 v1
        assertEquals(1, store.snapshot().versions().size());
        assertEquals("CN_V1", svc().evaluate("pricing", Map.of("region", "CN"),
                null, false, InputPolicy.REJECT).outputs().get("outcome"));
        // 失败事件可审计
        assertTrue(store.snapshot().auditLog().stream()
                .anyMatch(e -> e.action().equals("COMMIT") && !e.allowed()));
    }

    @Test
    void unauthorizedChangesAreRejectedAndAudited() {
        svc().commit("alice", ADMIN, "v1", List.of(pricingTable("CN_V1", "US_V1")));
        assertThrows(PermissionService.AccessDeniedException.class,
                () -> svc().commit("mallory", VIEWER, "evil",
                        List.of(pricingTable("HACK", "HACK"))));
        assertThrows(PermissionService.AccessDeniedException.class,
                () -> svc().activate("mallory", VIEWER, 1));
        assertThrows(PermissionService.AccessDeniedException.class,
                () -> svc().rollback("mallory", null, 1));
        // 未发生任何提交
        assertEquals(1, store.snapshot().versions().size());
        long denied = store.snapshot().auditLog().stream()
                .filter(e -> !e.allowed() && e.actor().equals("mallory")).count();
        assertTrue(denied >= 3, "越权尝试都应留痕，实际: " + denied);
    }

    @Test
    void activatingMissingVersionIsRejectedAndSnapshotUnchanged() {
        svc().commit("alice", ADMIN, "v1", List.of(pricingTable("CN_V1", "US_V1")));
        assertThrows(IllegalArgumentException.class,
                () -> svc().activate("alice", ADMIN, 99));
        assertEquals(1, store.snapshot().activeVersion());
    }

    @Test
    void concurrentEvaluationsAndCommitsAreConsistent() throws Exception {
        svc().commit("alice", ADMIN, "v1", List.of(pricingTable("CN_V1", "US_V1")));
        int threads = 16;
        int perThread = 200;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch halfway = new CountDownLatch(threads);
        CountDownLatch activated = new CountDownLatch(1);
        AtomicInteger errors = new AtomicInteger();
        // 每个评估线程只记录结果来自哪个版本、输出是否与版本一致
        Map<Long, Map<String, String>> expected = Map.of(
                1L, Map.of("CN", "CN_V1", "US", "US_V1"),
                2L, Map.of("CN", "CN_V2", "US", "US_V2"));
        ConcurrentHashMap<Long, AtomicInteger> seen = new ConcurrentHashMap<>();

        for (int t = 0; t < threads; t++) {
            final int idx = t;
            pool.submit(() -> {
                try {
                    start.await();
                    for (int i = 0; i < perThread; i++) {
                        // 前半段确保在 v1 上评估；后半段在 v2 激活后评估
                        if (i == perThread / 2) {
                            halfway.countDown();
                            activated.await();
                        }
                        String region = (i + idx) % 2 == 0 ? "CN" : "US";
                        EvaluationResult r = svc().evaluate("pricing",
                                Map.of("region", region), null, false, InputPolicy.REJECT);
                        seen.computeIfAbsent(r.version(), x -> new AtomicInteger()).incrementAndGet();
                        String want = expected.get(r.version()).get(region);
                        if (!want.equals(r.outputs().get("outcome"))) {
                            errors.incrementAndGet();
                        }
                    }
                } catch (Exception e) {
                    errors.incrementAndGet();
                }
            });
        }
        start.countDown();
        // 等所有线程在 v1 上跑过半，再发布 v2，随后放行后半段 -> 两版本必然都被观测到
        assertTrue(halfway.await(10, TimeUnit.SECONDS));
        svc().commit("bob", ADMIN, "v2", List.of(pricingTable("CN_V2", "US_V2")));
        svc().activate("bob", ADMIN, 2);
        activated.countDown();

        pool.shutdown();
        assertTrue(pool.awaitTermination(30, TimeUnit.SECONDS));
        assertEquals(0, errors.get(), "不允许出现版本字段混合或评估错误");
        assertTrue(seen.containsKey(1L));
        assertTrue(seen.containsKey(2L));
    }

    @Test
    void inFlightEvaluationReadsConsistentSnapshotDuringUpdate() throws Exception {
        svc().commit("alice", ADMIN, "v1", List.of(pricingTable("CN_V1", "US_V1")));
        // 反复提交/激活，同时持续评估，结果必须始终落在某完整版本上
        ExecutorService pool = Executors.newFixedThreadPool(8);
        CountDownLatch stop = new CountDownLatch(1);
        List<Throwable> problems = java.util.Collections.synchronizedList(new ArrayList<>());
        for (int i = 0; i < 7; i++) {
            pool.submit(() -> {
                try {
                    while (stop.getCount() > 0) {
                        EvaluationResult r = svc().evaluate("pricing",
                                Map.of("region", "CN"), null, false, InputPolicy.REJECT);
                        if (!"CN_V1".equals(r.outputs().get("outcome"))
                                && !"CN_V2".equals(r.outputs().get("outcome"))) {
                            problems.add(new AssertionError("异常输出: " + r.outputs()));
                        }
                    }
                } catch (Throwable e) {
                    problems.add(e);
                }
            });
        }
        for (int v = 2; v <= 10; v++) {
            svc().commit("bob", ADMIN, "v" + v, List.of(
                    v % 2 == 0 ? pricingTable("CN_V2", "US_V2")
                            : pricingTable("CN_V1", "US_V1")));
            svc().activate("bob", ADMIN, v);
        }
        stop.countDown();
        pool.shutdown();
        assertTrue(pool.awaitTermination(30, TimeUnit.SECONDS));
        assertTrue(problems.isEmpty(), problems.toString());
    }
}
