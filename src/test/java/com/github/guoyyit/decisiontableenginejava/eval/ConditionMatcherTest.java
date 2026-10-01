package com.github.guoyyit.decisiontableenginejava.eval;

import com.github.guoyyit.decisiontableenginejava.model.ConditionColumn;
import com.github.guoyyit.decisiontableenginejava.model.DataType;
import com.github.guoyyit.decisiontableenginejava.model.Operator;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class ConditionMatcherTest {

    private boolean m(ConditionColumn c, Object v) {
        return ConditionMatcher.matches(c, v, "t1", "r1", 0);
    }

    @Test
    void wildcardMatchesAnythingIncludingNull() {
        ConditionColumn c = new ConditionColumn("x", Operator.EQ, null,
                null, null, null, false, false, true);
        assertTrue(m(c, "a"));
        assertTrue(m(c, null));
    }

    @Test
    void presentRequiresNonNull() {
        ConditionColumn c = new ConditionColumn("x", Operator.PRESENT, null,
                null, null, null, false, false, false);
        assertTrue(m(c, ""));
        assertFalse(m(c, null));
    }

    @Test
    void eqNeInNotInWithNullNeverMatch() {
        ConditionColumn eq = new ConditionColumn("x", Operator.EQ, "A", null,
                null, null, false, false, false);
        assertFalse(m(eq, null));
        assertTrue(m(eq, "A"));
        assertFalse(m(eq, "B"));

        ConditionColumn ne = new ConditionColumn("x", Operator.NE, "A", null,
                null, null, false, false, false);
        assertFalse(m(ne, null)); // 缺失不是“不等”
        assertTrue(m(ne, "B"));

        ConditionColumn in = new ConditionColumn("x", Operator.IN, null,
                List.of("A", "B"), null, null, false, false, false);
        assertTrue(m(in, "B"));
        assertFalse(m(in, "C"));
        assertFalse(m(in, null));

        ConditionColumn notIn = new ConditionColumn("x", Operator.NOT_IN, null,
                List.of("A"), null, null, false, false, false);
        assertTrue(m(notIn, "B"));
        assertFalse(m(notIn, "A"));
        assertFalse(m(notIn, null));
    }

    @Test
    void numericComparisonsAcrossNumberTypes() {
        ConditionColumn gte = new ConditionColumn("x", Operator.GTE, 10, null,
                null, null, false, false, false);
        assertTrue(m(gte, 10L));
        assertTrue(m(gte, 10.0));
        assertFalse(m(gte, 9.99));

        ConditionColumn range = new ConditionColumn("x", Operator.RANGE, null,
                null, 1, 10, true, false, false); // [1, 10)
        assertTrue(m(range, 1));
        assertFalse(m(range, 10));
        assertTrue(m(range, 5));
    }

    @Test
    void typeMismatchIsExceptionLocatingRowAndColumn() {
        ConditionColumn gte = new ConditionColumn("x", Operator.GTE, 10, null,
                null, null, false, false, false);
        EvaluationException ex = assertThrows(EvaluationException.class,
                () -> m(gte, "ten"));
        assertEquals("t1", ex.tableId());
        assertEquals("r1", ex.rowId());
        assertEquals(0, ex.columnIndex());

        ConditionColumn eq = new ConditionColumn("x", Operator.EQ, "A", null,
                null, null, false, false, false);
        assertThrows(EvaluationException.class, () -> m(eq, 1));
    }
}
