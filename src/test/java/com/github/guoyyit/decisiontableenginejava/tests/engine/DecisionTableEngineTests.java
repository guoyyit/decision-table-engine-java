package com.github.guoyyit.decisiontableenginejava.tests.engine;

import com.github.guoyyit.decisiontableenginejava.engine.audit.AuditService;
import com.github.guoyyit.decisiontableenginejava.engine.eval.CompiledRuleSet;
import com.github.guoyyit.decisiontableenginejava.engine.model.ActionColumn;
import com.github.guoyyit.decisiontableenginejava.engine.model.ColumnType;
import com.github.guoyyit.decisiontableenginejava.engine.model.ConditionColumn;
import com.github.guoyyit.decisiontableenginejava.engine.model.ConflictDetail;
import com.github.guoyyit.decisiontableenginejava.engine.model.DecisionTable;
import com.github.guoyyit.decisiontableenginejava.engine.model.EvaluationResult;
import com.github.guoyyit.decisiontableenginejava.engine.model.EvaluationStatus;
import com.github.guoyyit.decisiontableenginejava.engine.model.ForbiddenException;
import com.github.guoyyit.decisiontableenginejava.engine.model.HitStrategy;
import com.github.guoyyit.decisiontableenginejava.engine.model.InvalidInputPolicy;
import com.github.guoyyit.decisiontableenginejava.engine.model.Operator;
import com.github.guoyyit.decisiontableenginejava.engine.model.PublishRequest;
import com.github.guoyyit.decisiontableenginejava.engine.model.RuleRow;
import com.github.guoyyit.decisiontableenginejava.engine.model.RuleSet;
import com.github.guoyyit.decisiontableenginejava.engine.model.RuleSetValidationException;
import com.github.guoyyit.decisiontableenginejava.engine.model.ValidationError;
import com.github.guoyyit.decisiontableenginejava.engine.store.RuleSetService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 决策表引擎综合测试。
 */
@SpringBootTest
class DecisionTableEngineTests {

    @Autowired
    RuleSetService ruleSetService;
    @Autowired
    AuditService auditService;

    // ---------- 构建辅助 ----------

    private ConditionColumn cond(String name, ColumnType type, Operator op) {
        return ConditionColumn.of(name, name, type, op);
    }

    private ConditionColumn cond(String name, ColumnType type, Operator op, boolean required) {
        return ConditionColumn.of(name, name, type, op, required);
    }

    private RuleRow row(String id, int priority, Map<String, Object> conditions, Map<String, Object> actions) {
        return RuleRow.of(id, priority, conditions, actions);
    }

    private DecisionTable table(String name,
                                List<ConditionColumn> conds,
                                List<ActionColumn> acts,
                                List<RuleRow> rows,
                                HitStrategy strategy,
                                InvalidInputPolicy policy,
                                Map<String, Object> degraded) {
        return DecisionTable.of(name, conds, acts, rows, strategy, policy, degraded);
    }

    private CompiledRuleSet publish(String name, List<DecisionTable> tables, int ratio,
                                    String grayKey, String user) {
        return ruleSetService.publish(
                new PublishRequest(name, tables, "test-" + name, ratio, grayKey), user);
    }

    private CompiledRuleSet publish(String name, List<DecisionTable> tables, int ratio, String user) {
        return publish(name, tables, ratio, null, user);
    }

    private String uniqueName(String prefix) {
        return prefix + "-" + UUID.randomUUID();
    }

    private Object actionOf(EvaluationResult r, String table, String action) {
        Map<String, Object> t = r.actionsByTable().get(table);
        return t == null ? null : t.get(action);
    }

    // ---------- 1. 版本切换 ----------

    @Test
    void versionSwitching_evaluationUsesLatestCommittedVersion() {
        String name = uniqueName("switch");
        ConditionColumn level = cond("level", ColumnType.STRING, Operator.EQ);
        ActionColumn result = ActionColumn.of("result", ColumnType.STRING);

        DecisionTable t1 = table("t1", List.of(level), List.of(result),
                List.of(row("r1", 10, Map.of("level", "A"), Map.of("result", "v1-action"))),
                HitStrategy.FIRST_MATCH, InvalidInputPolicy.REJECT, Map.of());
        publish(name, List.of(t1), 100, "admin");

        EvaluationResult r1 = ruleSetService.evaluate(name, Map.of("level", "A"), null);
        assertEquals(EvaluationStatus.HIT, r1.status());
        assertEquals(1, r1.version());
        assertEquals("v1-action", actionOf(r1, "t1", "result"));

        DecisionTable t2 = table("t1", List.of(level), List.of(result),
                List.of(row("r1", 10, Map.of("level", "A"), Map.of("result", "v2-action"))),
                HitStrategy.FIRST_MATCH, InvalidInputPolicy.REJECT, Map.of());
        publish(name, List.of(t2), 100, "admin");

        EvaluationResult r2 = ruleSetService.evaluate(name, Map.of("level", "A"), null);
        assertEquals(2, r2.version());
        assertEquals("v2-action", actionOf(r2, "t1", "result"));

        // 历史版本内容保持不变（不可变）
        RuleSet v1 = ruleSetService.getVersion(name, 1);
        assertEquals("v1-action", v1.tables().get(0).rows().get(0).actions().get("result"));
    }

    // ---------- 2. 回滚 ----------

    @Test
    void rollback_behaviorIdenticalToHistoricalVersion() {
        String name = uniqueName("rollback");
        ConditionColumn level = cond("level", ColumnType.STRING, Operator.EQ);
        ActionColumn result = ActionColumn.of("result", ColumnType.STRING);
        DecisionTable t1 = table("t1", List.of(level), List.of(result),
                List.of(row("r1", 10, Map.of("level", "A"), Map.of("result", "action-A"))),
                HitStrategy.FIRST_MATCH, InvalidInputPolicy.REJECT, Map.of());
        publish(name, List.of(t1), 100, "admin");
        EvaluationResult before = ruleSetService.evaluate(name, Map.of("level", "A"), null);

        DecisionTable t2 = table("t1", List.of(level), List.of(result),
                List.of(row("r1", 10, Map.of("level", "A"), Map.of("result", "action-B"))),
                HitStrategy.FIRST_MATCH, InvalidInputPolicy.REJECT, Map.of());
        publish(name, List.of(t2), 100, "admin");
        EvaluationResult v2 = ruleSetService.evaluate(name, Map.of("level", "A"), null);
        assertEquals("action-B", actionOf(v2, "t1", "result"));

        // 回滚到 v1：生成新版本 v3，内容与 v1 逐字段相同
        CompiledRuleSet rolled = ruleSetService.rollback(name, 1, "admin");
        assertEquals(3, rolled.ruleSet().version());
        EvaluationResult after = ruleSetService.evaluate(name, Map.of("level", "A"), null);
        assertEquals(3, after.version());
        assertEquals(before.status(), after.status());
        assertEquals(actionOf(before, "t1", "result"), actionOf(after, "t1", "result"));
        assertEquals(before.matchedRows(), after.matchedRows());

        // 版本历史中 v3 的表内容与 v1 完全一致
        RuleSet v1 = ruleSetService.getVersion(name, 1);
        RuleSet v3 = ruleSetService.getVersion(name, 3);
        assertEquals(v1.tables(), v3.tables());
    }

    // ---------- 3. 并发评估 ----------

    @Test
    void concurrentEvaluation_allResultsConsistent() throws Exception {
        String name = uniqueName("concurrent");
        ConditionColumn level = cond("level", ColumnType.STRING, Operator.EQ);
        ActionColumn result = ActionColumn.of("result", ColumnType.STRING);
        DecisionTable t1 = table("t1", List.of(level), List.of(result),
                List.of(row("r1", 10, Map.of("level", "A"), Map.of("result", "action-A"))),
                HitStrategy.FIRST_MATCH, InvalidInputPolicy.REJECT, Map.of());
        publish(name, List.of(t1), 100, "admin");

        int threads = 16;
        int iterations = 50;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        List<Callable<EvaluationResult>> tasks = new ArrayList<>();
        Map<String, Object> input = Map.of("level", "A");
        for (int i = 0; i < threads * iterations; i++) {
            tasks.add(() -> ruleSetService.evaluate(name, input, null));
        }
        List<Future<EvaluationResult>> futures = pool.invokeAll(tasks);
        pool.shutdown();
        assertTrue(pool.awaitTermination(30, TimeUnit.SECONDS));

        EvaluationResult expected = ruleSetService.evaluate(name, input, null);
        for (Future<EvaluationResult> f : futures) {
            EvaluationResult r = f.get();
            assertEquals(expected.version(), r.version());
            assertEquals(expected.status(), r.status());
            assertEquals(expected.actionsByTable(), r.actionsByTable());
            assertEquals(expected.matchedRows(), r.matchedRows());
        }
    }

    @Test
    void concurrentEvaluationDuringPublish_noHalfUpdatedState() throws Exception {
        String name = uniqueName("concurrent-pub");
        ConditionColumn level = cond("level", ColumnType.STRING, Operator.EQ);
        ActionColumn result = ActionColumn.of("result", ColumnType.STRING);
        DecisionTable t1 = table("t1", List.of(level), List.of(result),
                List.of(row("r1", 10, Map.of("level", "A"), Map.of("result", "action-A"))),
                HitStrategy.FIRST_MATCH, InvalidInputPolicy.REJECT, Map.of());
        publish(name, List.of(t1), 100, "admin");

        int evalThreads = 8;
        ExecutorService pool = Executors.newFixedThreadPool(evalThreads + 1);
        List<Callable<EvaluationResult>> tasks = new ArrayList<>();
        Map<String, Object> input = Map.of("level", "A");
        for (int i = 0; i < 200; i++) {
            tasks.add(() -> ruleSetService.evaluate(name, input, null));
        }
        // 发布线程：发布 v2（发布动作本身不返回 EvaluationResult，用一次评估占位）
        tasks.add(() -> {
            DecisionTable t2 = table("t1", List.of(level), List.of(result),
                    List.of(row("r1", 10, Map.of("level", "A"), Map.of("result", "action-B"))),
                    HitStrategy.FIRST_MATCH, InvalidInputPolicy.REJECT, Map.of());
            publish(name, List.of(t2), 100, "admin");
            return ruleSetService.evaluate(name, input, null);
        });
        List<Future<EvaluationResult>> futures = pool.invokeAll(tasks);
        pool.shutdown();
        assertTrue(pool.awaitTermination(30, TimeUnit.SECONDS));

        for (int i = 0; i < 200; i++) {
            EvaluationResult r = futures.get(i).get();
            // 每次评估要么看到 v1 要么看到 v2，且动作与版本严格对应（无字段级混合）
            assertTrue(r.version() == 1 || r.version() == 2, "意外版本: " + r.version());
            String action = (String) actionOf(r, "t1", "result");
            if (r.version() == 1) {
                assertEquals("action-A", action);
            } else {
                assertEquals("action-B", action);
            }
        }
    }

    // ---------- 4. 热更新失败 ----------

    @Test
    void hotUpdateFailure_oldVersionKeepsServing() {
        String name = uniqueName("hotfail");
        ConditionColumn level = cond("level", ColumnType.STRING, Operator.EQ);
        ActionColumn result = ActionColumn.of("result", ColumnType.STRING);
        DecisionTable valid = table("t1", List.of(level), List.of(result),
                List.of(row("r1", 10, Map.of("level", "A"), Map.of("result", "ok"))),
                HitStrategy.FIRST_MATCH, InvalidInputPolicy.REJECT, Map.of());
        publish(name, List.of(valid), 100, "admin");

        // 非法规则行：引用了不存在的条件列
        RuleRow bad = row("bad", 10, Map.of("notExist", "X"), Map.of("result", "x"));
        DecisionTable invalid = table("t1", List.of(level), List.of(result),
                List.of(bad), HitStrategy.FIRST_MATCH, InvalidInputPolicy.REJECT, Map.of());
        assertThrows(RuleSetValidationException.class,
                () -> publish(name, List.of(invalid), 100, "admin"));

        // 旧版本继续服务
        EvaluationResult r = ruleSetService.evaluate(name, Map.of("level", "A"), null);
        assertEquals(1, r.version());
        assertEquals(EvaluationStatus.HIT, r.status());
        assertEquals("ok", actionOf(r, "t1", "result"));

        // 审计记录 FAILURE
        boolean hasFailure = auditService.list().stream()
                .anyMatch(a -> a.action().equals("PUBLISH") && a.result().equals("FAILURE")
                        && a.target().contains(name));
        assertTrue(hasFailure, "应存在 PUBLISH FAILURE 审计记录");
    }

    // ---------- 5. 条件冲突 ----------

    @Test
    void conditionConflict_allMatchStrategyReturnsConflict() {
        String name = uniqueName("conflict");
        ConditionColumn flag = cond("flag", ColumnType.STRING, Operator.EQ);
        ActionColumn decision = ActionColumn.of("decision", ColumnType.STRING);
        DecisionTable t = table("t1", List.of(flag), List.of(decision),
                List.of(
                        row("r1", 5, Map.of("flag", "X"), Map.of("decision", "a1")),
                        row("r2", 5, Map.of("flag", "X"), Map.of("decision", "a2"))),
                HitStrategy.ALL_MATCH, InvalidInputPolicy.REJECT, Map.of());
        publish(name, List.of(t), 100, "admin");

        EvaluationResult r = ruleSetService.evaluate(name, Map.of("flag", "X"), null);
        assertEquals(EvaluationStatus.CONFLICT, r.status());
        assertEquals(1, r.conflicts().size());
        ConflictDetail c = r.conflicts().get(0);
        assertEquals("t1", c.tableName());
        assertEquals("decision", c.actionColumn());
        assertEquals(List.of("r1", "r2"), c.rowIds());
        assertEquals(List.of("a1", "a2"), c.values());
    }

    @Test
    void allMatch_consistentActions_mergedHit() {
        String name = uniqueName("allmatch");
        ConditionColumn flag = cond("flag", ColumnType.STRING, Operator.EQ);
        ActionColumn decision = ActionColumn.of("decision", ColumnType.STRING);
        DecisionTable t = table("t1", List.of(flag), List.of(decision),
                List.of(
                        row("r1", 5, Map.of("flag", "X"), Map.of("decision", "same")),
                        row("r2", 5, Map.of("flag", "X"), Map.of("decision", "same"))),
                HitStrategy.ALL_MATCH, InvalidInputPolicy.REJECT, Map.of());
        publish(name, List.of(t), 100, "admin");

        EvaluationResult r = ruleSetService.evaluate(name, Map.of("flag", "X"), null);
        assertEquals(EvaluationStatus.HIT, r.status());
        assertEquals("same", actionOf(r, "t1", "decision"));
    }

    // ---------- 6. 异常输入 ----------

    @Test
    void abnormalInput_missingField_rejectedWithLocation() {
        String name = uniqueName("missing");
        ConditionColumn age = cond("age", ColumnType.NUMBER, Operator.GE);
        ActionColumn decision = ActionColumn.of("decision", ColumnType.STRING);
        DecisionTable t = table("t1", List.of(age), List.of(decision),
                List.of(row("r1", 10, Map.of("age", 18), Map.of("decision", "adult"))),
                HitStrategy.FIRST_MATCH, InvalidInputPolicy.REJECT, Map.of());
        publish(name, List.of(t), 100, "admin");

        EvaluationResult r = ruleSetService.evaluate(name, Map.of(), null);
        assertEquals(EvaluationStatus.REJECT, r.status());
        ValidationError err = r.errors().get(0);
        assertEquals("MISSING_FIELD", err.code());
        assertEquals("age", err.columnName());
        assertEquals("age", err.field());
        assertEquals("t1", err.tableName());
        assertNotNull(err.message());
    }

    @Test
    void abnormalInput_typeMismatch_rejected() {
        String name = uniqueName("type");
        ConditionColumn age = cond("age", ColumnType.NUMBER, Operator.GE);
        ActionColumn decision = ActionColumn.of("decision", ColumnType.STRING);
        DecisionTable t = table("t1", List.of(age), List.of(decision),
                List.of(row("r1", 10, Map.of("age", 18), Map.of("decision", "adult"))),
                HitStrategy.FIRST_MATCH, InvalidInputPolicy.REJECT, Map.of());
        publish(name, List.of(t), 100, "admin");

        EvaluationResult r = ruleSetService.evaluate(name, Map.of("age", "eighteen"), null);
        assertEquals(EvaluationStatus.REJECT, r.status());
        assertEquals("TYPE_MISMATCH", r.errors().get(0).code());
        assertEquals("age", r.errors().get(0).columnName());
    }

    @Test
    void abnormalInput_outOfRange_rejected() {
        String name = uniqueName("range");
        ConditionColumn age = cond("age", ColumnType.NUMBER, Operator.GE).withRange(0, 150);
        ActionColumn decision = ActionColumn.of("decision", ColumnType.STRING);
        DecisionTable t = table("t1", List.of(age), List.of(decision),
                List.of(row("r1", 10, Map.of("age", 18), Map.of("decision", "adult"))),
                HitStrategy.FIRST_MATCH, InvalidInputPolicy.REJECT, Map.of());
        publish(name, List.of(t), 100, "admin");

        EvaluationResult r = ruleSetService.evaluate(name, Map.of("age", 200), null);
        assertEquals(EvaluationStatus.REJECT, r.status());
        assertEquals("OUT_OF_RANGE", r.errors().get(0).code());
        assertEquals("age", r.errors().get(0).columnName());
    }

    @Test
    void abnormalInput_degradePolicy_appliesDefaultActions() {
        String name = uniqueName("degrade");
        ConditionColumn age = cond("age", ColumnType.NUMBER, Operator.GE);
        ActionColumn decision = ActionColumn.of("decision", ColumnType.STRING, "fallback");
        DecisionTable t = table("t1", List.of(age), List.of(decision),
                List.of(row("r1", 10, Map.of("age", 18), Map.of("decision", "adult"))),
                HitStrategy.FIRST_MATCH, InvalidInputPolicy.DEGRADE,
                Map.of("decision", "manual-review"));
        publish(name, List.of(t), 100, "admin");

        EvaluationResult r = ruleSetService.evaluate(name, Map.of(), null);
        assertEquals(EvaluationStatus.DEGRADE, r.status());
        assertEquals("manual-review", actionOf(r, "t1", "decision"));
        assertEquals(1, r.errors().size());
    }

    // ---------- 7. 重复命中 ----------

    @Test
    void duplicateHit_samePriorityOverlap_deterministicAndFlagged() {
        String name = uniqueName("duplicate");
        ConditionColumn level = cond("level", ColumnType.STRING, Operator.EQ);
        ActionColumn decision = ActionColumn.of("decision", ColumnType.STRING);
        DecisionTable t = table("t1", List.of(level), List.of(decision),
                List.of(
                        row("r1", 5, Map.of("level", "X"), Map.of("decision", "first")),
                        row("r2", 5, Map.of("level", "X"), Map.of("decision", "second"))),
                HitStrategy.FIRST_MATCH, InvalidInputPolicy.REJECT, Map.of());
        publish(name, List.of(t), 100, "admin");

        EvaluationResult r1 = ruleSetService.evaluate(name, Map.of("level", "X"), null);
        assertEquals(EvaluationStatus.DUPLICATE_HIT, r1.status());
        assertEquals(2, r1.matchedRows().size());
        // 优先级相同时行号更小的行胜出（动作取 r1）
        assertEquals("first", actionOf(r1, "t1", "decision"));

        // 多次评估结果完全一致
        EvaluationResult r2 = ruleSetService.evaluate(name, Map.of("level", "X"), null);
        assertEquals(r1.status(), r2.status());
        assertEquals(r1.actionsByTable(), r2.actionsByTable());
        assertEquals(r1.matchedRows(), r2.matchedRows());
    }

    // ---------- 8. 无命中 ----------

    @Test
    void noHit_returnsDistinguishableStatus() {
        String name = uniqueName("nohit");
        ConditionColumn level = cond("level", ColumnType.STRING, Operator.EQ);
        ActionColumn decision = ActionColumn.of("decision", ColumnType.STRING);
        DecisionTable t = table("t1", List.of(level), List.of(decision),
                List.of(row("r1", 10, Map.of("level", "A"), Map.of("decision", "a"))),
                HitStrategy.FIRST_MATCH, InvalidInputPolicy.REJECT, Map.of());
        publish(name, List.of(t), 100, "admin");

        EvaluationResult r = ruleSetService.evaluate(name, Map.of("level", "C"), null);
        assertEquals(EvaluationStatus.NOT_HIT, r.status());
        assertTrue(r.matchedRows().isEmpty());
    }

    // ---------- 9. 灰度发布 ----------

    @Test
    void grayRelease_sameKeyAlwaysSameVersion_promoteMakesFull() {
        String name = uniqueName("gray");
        ConditionColumn level = cond("level", ColumnType.STRING, Operator.EQ);
        ActionColumn decision = ActionColumn.of("decision", ColumnType.STRING);
        DecisionTable t1 = table("t1", List.of(level), List.of(decision),
                List.of(row("r1", 10, Map.of("level", "A"), Map.of("decision", "old"))),
                HitStrategy.FIRST_MATCH, InvalidInputPolicy.REJECT, Map.of());
        publish(name, List.of(t1), 100, "admin");
        DecisionTable t2 = table("t1", List.of(level), List.of(decision),
                List.of(row("r1", 10, Map.of("level", "A"), Map.of("decision", "new"))),
                HitStrategy.FIRST_MATCH, InvalidInputPolicy.REJECT, Map.of());
        publish(name, List.of(t2), 50, "requestId", "admin");

        Map<String, Object> input = Map.of("level", "A");
        // 同一 key 多次评估永远路由到同一版本
        int v = ruleSetService.evaluate(name, input, "user-42").version();
        for (int i = 0; i < 20; i++) {
            assertEquals(v, ruleSetService.evaluate(name, input, "user-42").version());
        }
        // 扫描大量 key，两个版本都应被观察到（分流比例附近）
        boolean sawV1 = false, sawV2 = false;
        for (int i = 0; i < 2000; i++) {
            int ver = ruleSetService.evaluate(name, input, "key-" + i).version();
            if (ver == 1) {
                sawV1 = true;
            } else if (ver == 2) {
                sawV2 = true;
            }
            if (sawV1 && sawV2) {
                break;
            }
        }
        assertTrue(sawV1 && sawV2, "灰度分流应同时观察到 v1 与 v2");

        // 提升全量：所有流量走到新版本
        CompiledRuleSet promoted = ruleSetService.promote(name, "admin");
        assertEquals(3, promoted.ruleSet().version());
        for (int i = 0; i < 50; i++) {
            EvaluationResult r = ruleSetService.evaluate(name, input, "key-" + i);
            assertEquals(3, r.version());
            assertEquals("new", actionOf(r, "t1", "decision"));
        }
    }

    // ---------- 10. 权限 ----------

    @Test
    void permissionDenied_viewerCannotPublishOrRollback() {
        String name = uniqueName("perm");
        ConditionColumn level = cond("level", ColumnType.STRING, Operator.EQ);
        ActionColumn decision = ActionColumn.of("decision", ColumnType.STRING);
        DecisionTable t1 = table("t1", List.of(level), List.of(decision),
                List.of(row("r1", 10, Map.of("level", "A"), Map.of("decision", "a"))),
                HitStrategy.FIRST_MATCH, InvalidInputPolicy.REJECT, Map.of());

        assertThrows(ForbiddenException.class,
                () -> publish(name, List.of(t1), 100, "bob"));
        // 越权发布被拒绝，规则集不存在
        assertThrows(Exception.class, () -> ruleSetService.evaluate(name, Map.of("level", "A"), null));

        // admin 发布后，bob 回滚同样被拒绝
        publish(name, List.of(t1), 100, "admin");
        assertThrows(ForbiddenException.class,
                () -> ruleSetService.rollback(name, 1, "bob"));

        boolean denied = auditService.list().stream()
                .anyMatch(a -> a.result().equals("DENIED") && a.user().equals("bob"));
        assertTrue(denied, "应存在用户 bob 的 DENIED 审计记录");
    }

    // ---------- 11. 审计 ----------

    @Test
    void auditTrail_publishAndRollbackRecorded() {
        String name = uniqueName("audit");
        ConditionColumn level = cond("level", ColumnType.STRING, Operator.EQ);
        ActionColumn decision = ActionColumn.of("decision", ColumnType.STRING);
        DecisionTable t1 = table("t1", List.of(level), List.of(decision),
                List.of(row("r1", 10, Map.of("level", "A"), Map.of("decision", "a"))),
                HitStrategy.FIRST_MATCH, InvalidInputPolicy.REJECT, Map.of());
        publish(name, List.of(t1), 100, "admin");
        ruleSetService.rollback(name, 1, "admin");

        boolean publishOk = auditService.list().stream()
                .anyMatch(a -> a.action().equals("PUBLISH") && a.result().equals("SUCCESS")
                        && a.user().equals("admin") && a.target().contains(name));
        boolean rollbackOk = auditService.list().stream()
                .anyMatch(a -> a.action().equals("ROLLBACK") && a.result().equals("SUCCESS")
                        && a.user().equals("admin") && a.target().contains(name));
        assertTrue(publishOk);
        assertTrue(rollbackOk);
    }

    // ---------- 12. 性能 ----------

    @Test
    void performance_indexedEvaluation_hasKnownUpperBound() {
        String name = uniqueName("perf");
        ConditionColumn country = cond("country", ColumnType.STRING, Operator.EQ);
        ConditionColumn amount = cond("amount", ColumnType.NUMBER, Operator.GE);
        ActionColumn rate = ActionColumn.of("rate", ColumnType.NUMBER);

        List<RuleRow> rows = new ArrayList<>();
        for (int i = 0; i < 10_000; i++) {
            String c = "C" + (i % 10);
            int threshold = (i % 50) * 100;
            rows.add(row("r" + i, i % 100,
                    Map.of("country", c, "amount", threshold),
                    Map.of("rate", 0.01 + (i % 10) * 0.001)));
        }
        DecisionTable t = table("t1", List.of(country, amount), List.of(rate), rows,
                HitStrategy.FIRST_MATCH, InvalidInputPolicy.REJECT, Map.of());
        publish(name, List.of(t), 100, "admin");

        Map<String, Object> input = Map.of("country", "C5", "amount", 2500);
        ruleSetService.evaluate(name, input, null); // 预热
        int warmup = 100;
        int measured = 1000;
        long total = 0;
        long max = 0;
        EvaluationStatus lastStatus = null;
        for (int i = 0; i < warmup + measured; i++) {
            long s = System.nanoTime();
            EvaluationResult r = ruleSetService.evaluate(name, input, null);
            long e = System.nanoTime() - s;
            // 性能数据中同优先级行可能重叠，HIT 或 DUPLICATE_HIT 均属正常命中
            assertTrue(r.status() == EvaluationStatus.HIT
                            || r.status() == EvaluationStatus.DUPLICATE_HIT,
                    "应有命中动作输出，实际状态: " + r.status());
            lastStatus = r.status();
            if (i >= warmup) {
                total += e;
                max = Math.max(max, e);
            }
        }
        long avgUs = total / measured / 1000;
        long maxUs = max / 1000;
        System.out.println("[perf] 10k rows indexed: avg=" + avgUs + "us, max=" + maxUs
                + "us, status=" + lastStatus);
        // 索引命中时候选行数量级为 R/10 * 条件选择，单次评估应在毫秒级以内
        assertTrue(avgUs < 5_000, "平均耗时应 < 5ms，实际 " + avgUs + "us");
    }

    @Test
    void performance_noIndexWorstCase_stillBounded() {
        String name = uniqueName("perf-scan");
        ConditionColumn amount = cond("amount", ColumnType.NUMBER, Operator.GE);
        ActionColumn rate = ActionColumn.of("rate", ColumnType.NUMBER);

        List<RuleRow> rows = new ArrayList<>();
        for (int i = 0; i < 10_000; i++) {
            rows.add(row("r" + i, i % 100,
                    Map.of("amount", i),
                    Map.of("rate", 0.01)));
        }
        DecisionTable t = table("t1", List.of(amount), List.of(rate), rows,
                HitStrategy.FIRST_MATCH, InvalidInputPolicy.REJECT, Map.of());
        publish(name, List.of(t), 100, "admin");

        Map<String, Object> input = Map.of("amount", 9999);
        ruleSetService.evaluate(name, input, null);
        long s = System.nanoTime();
        EvaluationResult r = ruleSetService.evaluate(name, input, null);
        long e = System.nanoTime() - s;
        System.out.println("[perf] 10k rows full-scan: " + (e / 1000) + "us, status=" + r.status());
        assertTrue(r.status() == EvaluationStatus.HIT
                        || r.status() == EvaluationStatus.DUPLICATE_HIT,
                "应有命中动作输出，实际状态: " + r.status());
        assertTrue(e < 200_000_000L, "全表扫描耗时应 < 200ms");
    }

    // ---------- 13. 确定性 ----------

    @Test
    void determinism_inputMapOrderDoesNotMatter() {
        String name = uniqueName("determinism");
        ConditionColumn a = cond("a", ColumnType.STRING, Operator.EQ);
        ConditionColumn b = cond("b", ColumnType.NUMBER, Operator.GE);
        ActionColumn decision = ActionColumn.of("decision", ColumnType.STRING);
        DecisionTable t = table("t1", List.of(a, b), List.of(decision),
                List.of(row("r1", 10, Map.of("a", "X", "b", 10), Map.of("decision", "ok"))),
                HitStrategy.FIRST_MATCH, InvalidInputPolicy.REJECT, Map.of());
        publish(name, List.of(t), 100, "admin");

        Map<String, Object> input1 = new HashMap<>();
        input1.put("a", "X");
        input1.put("b", 20);
        Map<String, Object> input2 = new HashMap<>();
        input2.put("b", 20);
        input2.put("a", "X");

        EvaluationResult r1 = ruleSetService.evaluate(name, input1, null);
        EvaluationResult r2 = ruleSetService.evaluate(name, input2, null);
        assertEquals(r1.status(), r2.status());
        assertEquals(r1.actionsByTable(), r2.actionsByTable());
        assertEquals(r1.matchedRows(), r2.matchedRows());
        assertEquals(r1.version(), r2.version());
    }
}
