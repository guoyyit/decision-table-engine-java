package com.github.guoyyit.decisiontableenginejava.support;

import com.github.guoyyit.decisiontableenginejava.model.ActionColumn;
import com.github.guoyyit.decisiontableenginejava.model.ConditionColumn;
import com.github.guoyyit.decisiontableenginejava.model.DataType;
import com.github.guoyyit.decisiontableenginejava.model.DecisionTable;
import com.github.guoyyit.decisiontableenginejava.model.FieldSchema;
import com.github.guoyyit.decisiontableenginejava.model.HitPolicy;
import com.github.guoyyit.decisiontableenginejava.model.Operator;
import com.github.guoyyit.decisiontableenginejava.model.RuleRow;
import com.github.guoyyit.decisiontableenginejava.model.TiePolicy;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** 测试用决策表构造夹具。 */
public final class TestTables {

    private TestTables() {
    }

    public static FieldSchema str(String name, boolean required, String... allowed) {
        return new FieldSchema(name, DataType.STRING, required, null, null,
                allowed.length == 0 ? null : List.of(allowed));
    }

    public static FieldSchema num(String name, boolean required, Double min, Double max) {
        return new FieldSchema(name, DataType.NUMBER, required, min, max, null);
    }

    public static FieldSchema bool(String name, boolean required) {
        return new FieldSchema(name, DataType.BOOLEAN, required, null, null, null);
    }

    public static ConditionColumn eq(String field, Object value) {
        return new ConditionColumn(field, Operator.EQ, value, null, null, null,
                false, false, false);
    }

    public static ConditionColumn in(String field, Object... values) {
        return new ConditionColumn(field, Operator.IN, null, List.of(values),
                null, null, false, false, false);
    }

    public static ConditionColumn gte(String field, Object value) {
        return new ConditionColumn(field, Operator.GTE, value, null, null, null,
                false, false, false);
    }

    public static ConditionColumn wildcard() {
        return new ConditionColumn(null, null, null, null, null, null,
                false, false, true);
    }

    public static RuleRow row(String rowId, int priority,
                              List<ConditionColumn> conditions,
                              Map<String, Object> actions) {
        return new RuleRow(rowId, priority, conditions, actions);
    }

    public static Map<String, Object> actions(Object... kv) {
        Map<String, Object> m = new LinkedHashMap<>();
        for (int i = 0; i < kv.length; i += 2) {
            m.put((String) kv[i], kv[i + 1]);
        }
        return m;
    }

    public static Map<String, FieldSchema> fields(FieldSchema... f) {
        Map<String, FieldSchema> m = new LinkedHashMap<>();
        for (FieldSchema s : f) {
            m.put(s.name(), s);
        }
        return m;
    }

    public static DecisionTable table(String id, HitPolicy hit, TiePolicy tie,
                                      Map<String, FieldSchema> fields,
                                      List<String> actionFields,
                                      RuleRow... rows) {
        List<ActionColumn> cols = new ArrayList<>();
        actionFields.forEach(af -> cols.add(new ActionColumn(af, DataType.STRING)));
        return new DecisionTable(id, id, hit, tie, fields, cols, List.of(rows));
    }
}
