package com.github.guoyyit.decisiontableenginejava.eval;

import com.github.guoyyit.decisiontableenginejava.engine.CompiledTable;
import com.github.guoyyit.decisiontableenginejava.model.DecisionTable;
import com.github.guoyyit.decisiontableenginejava.model.HitPolicy;
import com.github.guoyyit.decisiontableenginejava.model.TiePolicy;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static com.github.guoyyit.decisiontableenginejava.support.TestTables.*;
import static org.junit.jupiter.api.Assertions.*;

class TableEvaluatorTest {

    private final TableEvaluator evaluator = new TableEvaluator(new InputValidator());

    private EvaluationResult run(DecisionTable t, Map<String, Object> input) {
        return evaluator.evaluate(t.tableId(), CompiledTable.compile(t), input,
                1, false, true, Map.of());
    }

    @Test
    void firstPolicyPicksDefinitionOrder() {
        DecisionTable t = table("t", HitPolicy.FIRST, TiePolicy.CONFLICT,
                fields(str("region", true, "CN", "US")), List.of("outcome"),
                row("r1", 5, List.of(wildcard()), actions("outcome", "WILDCARD")),
                row("r2", 0, List.of(eq("region", "CN")), actions("outcome", "CN")));
        EvaluationResult r = run(t, Map.of("region", "CN"));
        assertEquals(EvalStatus.HIT, r.status());
        assertEquals("WILDCARD", r.outputs().get("outcome"));
        assertEquals(List.of("r1"), r.matchedRowIds());
    }

    @Test
    void uniquePolicyReturnsConflictOnMultipleMatches() {
        DecisionTable t = table("t", HitPolicy.UNIQUE, TiePolicy.CONFLICT,
                fields(str("region", true, "CN", "US")), List.of("outcome"),
                row("r1", 0, List.of(eq("region", "CN")), actions("outcome", "A")),
                row("r2", 0, List.of(eq("region", "CN")), actions("outcome", "B")));
        EvaluationResult r = run(t, Map.of("region", "CN"));
        assertEquals(EvalStatus.CONFLICT, r.status());
        assertEquals(List.of("r1", "r2"), r.matchedRowIds());
        assertTrue(r.outputs().isEmpty());
    }

    @Test
    void priorityTieConflictDuplicateAndLowestId() {
        DecisionTable conflict = table("t", HitPolicy.PRIORITY, TiePolicy.CONFLICT,
                fields(str("region", true, "CN")), List.of("outcome"),
                row("r1", 0, List.of(eq("region", "CN")), actions("outcome", "A")),
                row("r2", 0, List.of(eq("region", "CN")), actions("outcome", "B")),
                row("r3", 5, List.of(eq("region", "CN")), actions("outcome", "C")));
        EvaluationResult cr = run(conflict, Map.of("region", "CN"));
        assertEquals(EvalStatus.CONFLICT, cr.status());
        assertEquals(List.of("r1", "r2"), cr.matchedRowIds());

        DecisionTable duplicate = table("t", HitPolicy.PRIORITY, TiePolicy.CONFLICT,
                fields(str("region", true, "CN")), List.of("outcome"),
                row("r1", 0, List.of(eq("region", "CN")), actions("outcome", "A")),
                row("r2", 0, List.of(eq("region", "CN")), actions("outcome", "A")));
        EvaluationResult dr = run(duplicate, Map.of("region", "CN"));
        assertEquals(EvalStatus.DUPLICATE_HIT, dr.status());
        assertEquals("A", dr.outputs().get("outcome"));

        DecisionTable lowest = table("t", HitPolicy.PRIORITY, TiePolicy.LOWEST_ID,
                fields(str("region", true, "CN")), List.of("outcome"),
                row("r9", 0, List.of(eq("region", "CN")), actions("outcome", "Z")),
                row("r1", 0, List.of(eq("region", "CN")), actions("outcome", "A")));
        EvaluationResult lr = run(lowest, Map.of("region", "CN"));
        assertEquals(EvalStatus.HIT, lr.status());
        assertEquals("A", lr.outputs().get("outcome"));
        assertTrue(lr.reason().contains("r1"));
    }

    @Test
    void priorityHigherPriorityWins() {
        DecisionTable t = table("t", HitPolicy.PRIORITY, TiePolicy.CONFLICT,
                fields(str("region", true, "CN")), List.of("outcome"),
                row("low", 9, List.of(eq("region", "CN")), actions("outcome", "LOW")),
                row("high", 1, List.of(eq("region", "CN")), actions("outcome", "HIGH")));
        EvaluationResult r = run(t, Map.of("region", "CN"));
        assertEquals(EvalStatus.HIT, r.status());
        assertEquals("HIGH", r.outputs().get("outcome"));
    }

    @Test
    void collectPolicyGathersActionsAndListsDifferingValues() {
        DecisionTable t = table("t", HitPolicy.COLLECT, TiePolicy.CONFLICT,
                fields(str("region", true, "CN")), List.of("outcome"),
                row("r2", 0, List.of(eq("region", "CN")), actions("outcome", "B")),
                row("r1", 0, List.of(eq("region", "CN")), actions("outcome", "A")));
        EvaluationResult r = run(t, Map.of("region", "CN"));
        assertEquals(EvalStatus.HIT, r.status());
        // 按 rowId 排序输出
        assertEquals(List.of("A", "B"), r.outputs().get("outcome"));
    }

    @Test
    void noHitIsDistinguishable() {
        DecisionTable t = table("t", HitPolicy.UNIQUE, TiePolicy.CONFLICT,
                fields(str("region", true, "CN", "US")), List.of("outcome"),
                row("r1", 0, List.of(eq("region", "CN")), actions("outcome", "A")));
        EvaluationResult r = run(t, Map.of("region", "US"));
        assertEquals(EvalStatus.NO_HIT, r.status());
    }

    @Test
    void invalidInputRejectedWithLocationAndDegradeOption() {
        DecisionTable t = table("t", HitPolicy.FIRST, TiePolicy.CONFLICT,
                fields(str("region", true, "CN")), List.of("outcome"),
                row("r1", 0, List.of(eq("region", "CN")), actions("outcome", "A")));
        EvaluationResult rejected = evaluator.evaluate("t", CompiledTable.compile(t),
                Map.of(), 1, false, true, Map.of());
        assertEquals(EvalStatus.REJECTED, rejected.status());
        assertEquals("region", rejected.errors().get(0).field());

        EvaluationResult degraded = evaluator.evaluate("t", CompiledTable.compile(t),
                Map.of(), 1, false, false, Map.of("outcome", "DEFAULT"));
        assertEquals(EvalStatus.DEGRADED, degraded.status());
        assertEquals("DEFAULT", degraded.outputs().get("outcome"));
    }

    @Test
    void traceCoversCandidateRowsWithColumnLocationAndCounts() {
        DecisionTable t = table("t", HitPolicy.FIRST, TiePolicy.CONFLICT,
                fields(str("region", true, "CN", "US")), List.of("outcome"),
                row("r1", 0, List.of(eq("region", "CN")), actions("outcome", "A")),
                row("r2", 0, List.of(eq("region", "US")), actions("outcome", "B")));
        EvaluationResult r = run(t, Map.of("region", "US"));
        // region 列可建哈希索引：US 只对应 r2，r1 被预筛，不逐列判定
        assertEquals(2, r.totalRows());
        assertEquals(1, r.evaluatedRows());
        assertEquals(1, r.trace().size());
        assertEquals("r2", r.trace().get(0).rowId());
        assertTrue(r.trace().get(0).matched());

        // 通配列不可索引 -> 全表扫描，trace 中未命中行带失败列号
        DecisionTable t2 = table("t2", HitPolicy.FIRST, TiePolicy.CONFLICT,
                fields(str("region", true, "CN", "US")), List.of("outcome"),
                row("r1", 0, List.of(eq("region", "CN")), actions("outcome", "A")),
                row("r2", 0, List.of(wildcard()), actions("outcome", "W")));
        EvaluationResult r2 = run(t2, Map.of("region", "US"));
        assertEquals(2, r2.evaluatedRows());
        TraceEntry failEntry = r2.trace().get(0);
        assertFalse(failEntry.matched());
        assertEquals(0, failEntry.failedColumn());
    }

    @Test
    void sameInputIsRepeatedlyReproducibleAcrossShuffledInputMaps() {
        DecisionTable t = table("t", HitPolicy.PRIORITY, TiePolicy.CONFLICT,
                fields(str("region", true, "CN", "US")), List.of("outcome"),
                row("r1", 0, List.of(eq("region", "CN")), actions("outcome", "A")),
                row("r2", 0, List.of(eq("region", "US")), actions("outcome", "B")));
        Map<String, Object> m1 = new java.util.HashMap<>();
        m1.put("region", "US");
        Map<String, Object> m2 = new java.util.TreeMap<>();
        m2.put("region", "US");
        for (int i = 0; i < 20; i++) {
            EvaluationResult a = run(t, m1);
            EvaluationResult b = run(t, m2);
            assertEquals(a.outputs(), b.outputs());
            assertEquals(a.matchedRowIds(), b.matchedRowIds());
            assertEquals("B", a.outputs().get("outcome"));
        }
    }
}
