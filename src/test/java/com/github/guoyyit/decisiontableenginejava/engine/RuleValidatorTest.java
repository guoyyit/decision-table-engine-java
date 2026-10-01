package com.github.guoyyit.decisiontableenginejava.engine;

import com.github.guoyyit.decisiontableenginejava.model.ConditionColumn;
import com.github.guoyyit.decisiontableenginejava.model.DecisionTable;
import com.github.guoyyit.decisiontableenginejava.model.HitPolicy;
import com.github.guoyyit.decisiontableenginejava.model.Operator;
import com.github.guoyyit.decisiontableenginejava.model.TiePolicy;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static com.github.guoyyit.decisiontableenginejava.support.TestTables.*;
import static org.junit.jupiter.api.Assertions.*;

class RuleValidatorTest {

    private final RuleValidator validator = new RuleValidator();

    private DecisionTable validTable(String id) {
        return table(id, HitPolicy.UNIQUE, TiePolicy.CONFLICT,
                fields(str("eq", true, "CN", "US"), num("age", true, 0.0, 150.0)),
                List.of("outcome"),
                row("r1", 0, List.of(eq("eq", "CN"), gte("age", 18)),
                        actions("outcome", "ADULT_CN")),
                row("r2", 0, List.of(eq("eq", "US"), wildcard()),
                        actions("outcome", "US_ANY")));
    }

    @Test
    void validTablePasses() {
        assertDoesNotThrow(() -> validator.validateAll(List.of(validTable("t1"))));
    }

    @Test
    void duplicateTableAndRowIdsRejected() {
        RuleValidator.RuleValidationException ex = assertThrows(
                RuleValidator.RuleValidationException.class,
                () -> validator.validateAll(List.of(validTable("t1"), validTable("t1"))));
        assertTrue(ex.problems().stream().anyMatch(p -> p.contains("决策表 ID 重复")));

        DecisionTable dupRows = table("t2", HitPolicy.FIRST, TiePolicy.CONFLICT,
                fields(str("region", true, "CN")), List.of("outcome"),
                row("r1", 0, List.of(eq("region", "CN")), actions("outcome", "A")),
                row("r1", 1, List.of(eq("region", "CN")), actions("outcome", "B")));
        RuleValidator.RuleValidationException ex2 = assertThrows(
                RuleValidator.RuleValidationException.class,
                () -> validator.validate(dupRows));
        assertTrue(ex2.problems().stream().anyMatch(p -> p.contains("行 ID 为空或重复")));
    }

    @Test
    void conditionOnUnknownFieldAndMissingParamRejected() {
        ConditionColumn bad = new ConditionColumn("nope", Operator.EQ, "X", null,
                null, null, false, false, false);
        ConditionColumn missingValue = new ConditionColumn("region", Operator.EQ, null,
                null, null, null, false, false, false);
        DecisionTable t = table("t3", HitPolicy.FIRST, TiePolicy.CONFLICT,
                fields(str("region", true, "CN")), List.of("outcome"),
                row("r1", 0, List.of(bad, missingValue), actions("outcome", "A")));
        RuleValidator.RuleValidationException ex = assertThrows(
                RuleValidator.RuleValidationException.class, () -> validator.validate(t));
        assertTrue(ex.problems().stream().anyMatch(p -> p.contains("未声明的输入字段")));
        assertTrue(ex.problems().stream().anyMatch(p -> p.contains("缺少 value 参数")));
    }

    @Test
    void literalTypeMismatchRejected() {
        DecisionTable t = table("t4", HitPolicy.FIRST, TiePolicy.CONFLICT,
                fields(num("age", true, 0.0, 150.0)), List.of("outcome"),
                row("r1", 0, List.of(eq("age", "eighteen")), actions("outcome", "A")));
        RuleValidator.RuleValidationException ex = assertThrows(
                RuleValidator.RuleValidationException.class, () -> validator.validate(t));
        assertTrue(ex.problems().stream().anyMatch(p -> p.contains("字面量")
                && p.contains("类型不匹配")));
    }

    @Test
    void actionSetMismatchRejected() {
        DecisionTable t = table("t5", HitPolicy.FIRST, TiePolicy.CONFLICT,
                fields(str("region", true, "CN")), List.of("outcome"),
                row("r1", 0, List.of(eq("region", "CN")), Map.of("other", "X")));
        RuleValidator.RuleValidationException ex = assertThrows(
                RuleValidator.RuleValidationException.class, () -> validator.validate(t));
        assertTrue(ex.problems().stream().anyMatch(p -> p.contains("动作字段集合")));
    }

    @Test
    void rangeValidationRequiresNumbersAndBounds() {
        ConditionColumn stringRange = new ConditionColumn("region", Operator.RANGE, null,
                null, 1, 2, true, true, false);
        DecisionTable t = table("t6", HitPolicy.FIRST, TiePolicy.CONFLICT,
                fields(str("region", true, "CN")), List.of("outcome"),
                row("r1", 0, List.of(stringRange), actions("outcome", "A")));
        RuleValidator.RuleValidationException ex = assertThrows(
                RuleValidator.RuleValidationException.class, () -> validator.validate(t));
        assertTrue(ex.problems().stream().anyMatch(p -> p.contains("RANGE 仅支持 NUMBER")));
    }
}
