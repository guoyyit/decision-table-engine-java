package com.github.guoyyit.decisiontableenginejava.engine;

import com.github.guoyyit.decisiontableenginejava.eval.EvalStatus;
import com.github.guoyyit.decisiontableenginejava.eval.EvaluationResult;
import com.github.guoyyit.decisiontableenginejava.eval.InputValidator;
import com.github.guoyyit.decisiontableenginejava.eval.TableEvaluator;
import com.github.guoyyit.decisiontableenginejava.model.ActionColumn;
import com.github.guoyyit.decisiontableenginejava.model.ConditionColumn;
import com.github.guoyyit.decisiontableenginejava.model.DataType;
import com.github.guoyyit.decisiontableenginejava.model.DecisionTable;
import com.github.guoyyit.decisiontableenginejava.model.FieldSchema;
import com.github.guoyyit.decisiontableenginejava.model.HitPolicy;
import com.github.guoyyit.decisiontableenginejava.model.Operator;
import com.github.guoyyit.decisiontableenginejava.model.RuleRow;
import com.github.guoyyit.decisiontableenginejava.model.TiePolicy;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class CompiledTableIndexTest {

    private final TableEvaluator evaluator = new TableEvaluator(new InputValidator());

    private DecisionTable bigIndexedTable(int n) {
        Map<String, FieldSchema> fields = new LinkedHashMap<>();
        fields.put("segment", new FieldSchema("segment", DataType.STRING, true, null, null, null));
        List<ActionColumn> actions = List.of(new ActionColumn("offer", DataType.STRING));
        List<RuleRow> rows = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            ConditionColumn c = new ConditionColumn("segment", Operator.EQ, "seg-" + i,
                    null, null, null, false, false, false);
            rows.add(new RuleRow("row-" + i, 0, List.of(c), Map.of("offer", "offer-" + i)));
        }
        return new DecisionTable("big", "big", HitPolicy.UNIQUE, TiePolicy.CONFLICT,
                fields, actions, rows);
    }

    @Test
    void indexReducesEvaluatedRowsToOne() {
        int n = 5000;
        CompiledTable compiled = CompiledTable.compile(bigIndexedTable(n));
        assertTrue(compiled.hasIndex());
        assertTrue(compiled.indexedColumns().contains("segment"));

        EvaluationResult r = evaluator.evaluate("big", compiled,
                Map.of("segment", "seg-4321"), 1, false, true, Map.of());
        assertEquals(EvalStatus.HIT, r.status());
        assertEquals("offer-4321", r.outputs().get("offer"));
        assertEquals(n, r.totalRows());
        assertEquals(1, r.evaluatedRows(), "哈希索引应把候选缩减为 1 行");
    }

    @Test
    void indexedEvaluationTimeIsBoundedAndStableAcrossRepeatedCalls() {
        int n = 10_000;
        CompiledTable compiled = CompiledTable.compile(bigIndexedTable(n));
        // 预热
        for (int i = 0; i < 50; i++) {
            evaluator.evaluate("big", compiled, Map.of("segment", "seg-" + (i * 37 % n)),
                    1, false, true, Map.of());
        }
        long maxNanos = 0;
        for (int i = 0; i < 200; i++) {
            EvaluationResult r = evaluator.evaluate("big", compiled,
                    Map.of("segment", "seg-" + (i * 97 % n)), 1, false, true, Map.of());
            assertEquals(EvalStatus.HIT, r.status());
            maxNanos = Math.max(maxNanos, r.elapsedNanos());
        }
        // 宽松的工程上界：10000 行的索引点查应稳定在 2ms 以内（CI 噪声下仍然宽松）
        assertTrue(maxNanos < 2_000_000L,
                "单次评估耗时超过上界: " + maxNanos + "ns");
    }

    @Test
    void wildcardColumnDisablesIndexAndFallsBackToFullScan() {
        FieldSchema segment = new FieldSchema("segment", DataType.STRING, true, null, null, null);
        DecisionTable t = new DecisionTable("t", "t", HitPolicy.FIRST, TiePolicy.CONFLICT,
                Map.of("segment", segment),
                List.of(new ActionColumn("offer", DataType.STRING)),
                List.of(
                        new RuleRow("r1", 0,
                                List.of(new ConditionColumn("segment", Operator.EQ, "A",
                                        null, null, null, false, false, false)),
                                Map.of("offer", "OA")),
                        new RuleRow("r2", 0,
                                List.of(new ConditionColumn("segment", Operator.EQ, null,
                                        null, null, null, false, false, true)),
                                Map.of("offer", "OW"))));
        CompiledTable compiled = CompiledTable.compile(t);
        assertFalse(compiled.hasIndex(), "含通配的列不可索引");
        EvaluationResult r = evaluator.evaluate("t", compiled,
                Map.of("segment", "ZZZ"), 1, false, true, Map.of());
        assertEquals(EvalStatus.HIT, r.status());
        assertEquals(2, r.evaluatedRows());
        assertEquals("OW", r.outputs().get("offer")); // FIRST 命中通配行
    }

    @Test
    void noIndexValueMeansEmptyCandidatesAndNoHit() {
        CompiledTable compiled = CompiledTable.compile(bigIndexedTable(100));
        EvaluationResult r = evaluator.evaluate("big", compiled,
                Map.of("segment", "seg-MISSING"), 1, false, true, Map.of());
        assertEquals(EvalStatus.NO_HIT, r.status());
        assertEquals(0, r.evaluatedRows());
    }
}
