package com.github.guoyyit.decisiontableenginejava.engine;

import com.github.guoyyit.decisiontableenginejava.model.ConditionColumn;
import com.github.guoyyit.decisiontableenginejava.model.DataType;
import com.github.guoyyit.decisiontableenginejava.model.DecisionTable;
import com.github.guoyyit.decisiontableenginejava.model.Operator;
import com.github.guoyyit.decisiontableenginejava.model.RuleRow;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * 规则集静态校验器：结构完整性、类型一致性、参数完整性、ID 唯一性。
 * 提交前调用，收集全部问题后抛 RuleValidationException，
 * 提交被整体拒绝，引擎继续使用上一个可用版本。
 */
@Component
public class RuleValidator {

    /** 校验失败：携带全部问题描述，便于一次性修正。 */
    public static class RuleValidationException extends RuntimeException {
        private final List<String> problems;

        public RuleValidationException(List<String> problems) {
            super("规则校验失败: " + problems);
            this.problems = List.copyOf(problems);
        }

        public List<String> problems() {
            return problems;
        }
    }

    public void validateAll(List<DecisionTable> tables) {
        List<String> problems = new ArrayList<>();
        Set<String> tableIds = new HashSet<>();
        for (DecisionTable table : tables) {
            if (table == null) {
                problems.add("决策表列表中存在 null 元素");
                continue;
            }
            if (!tableIds.add(table.tableId())) {
                problems.add("决策表 ID 重复: " + table.tableId());
            }
            validate(table, problems);
        }
        if (!problems.isEmpty()) {
            throw new RuleValidationException(problems);
        }
    }

    public void validate(DecisionTable table) {
        List<String> problems = new ArrayList<>();
        validate(table, problems);
        if (!problems.isEmpty()) {
            throw new RuleValidationException(problems);
        }
    }

    private void validate(DecisionTable table, List<String> problems) {
        String t = "表[" + table.tableId() + "] ";
        if (isBlank(table.tableId())) {
            problems.add("决策表 ID 不能为空");
        }
        if (table.hitPolicy() == null) {
            problems.add(t + "命中策略不能为空");
        }
        if (table.tiePolicy() == null) {
            problems.add(t + "同优先级裁决策略不能为空");
        }

        table.inputFields().forEach((name, f) -> {
            if (isBlank(name) || !name.equals(f.name())) {
                problems.add(t + "输入字段名不一致: " + name);
            }
            if (f.type() == DataType.STRING
                    && ((f.min() != null) || (f.max() != null))) {
                problems.add(t + "字段 " + name + " 为 STRING 类型，不允许 min/max");
            }
            if (f.type() != DataType.STRING && !f.allowed().isEmpty()) {
                problems.add(t + "字段 " + name + " 非 STRING 类型，不允许 allowed");
            }
            if (f.type() == DataType.NUMBER && f.min() != null && f.max() != null
                    && f.min() > f.max()) {
                problems.add(t + "字段 " + name + " 的 min 大于 max");
            }
        });

        Set<String> actionFields = new HashSet<>();
        table.actionColumns().forEach(a -> {
            if (isBlank(a.field())) {
                problems.add(t + "动作列字段名不能为空");
            } else if (!actionFields.add(a.field())) {
                problems.add(t + "动作列字段重复: " + a.field());
            }
            if (a.type() == null) {
                problems.add(t + "动作列 " + a.field() + " 类型不能为空");
            }
        });

        Set<String> rowIds = new HashSet<>();
        for (RuleRow row : table.rows()) {
            String r = t + "行[" + row.rowId() + "] ";
            if (isBlank(row.rowId()) || !rowIds.add(row.rowId())) {
                problems.add(r + "行 ID 为空或重复");
            }
            if (row.priority() < 0) {
                problems.add(r + "优先级不能为负数");
            }
            for (int i = 0; i < row.conditions().size(); i++) {
                ConditionColumn c = row.conditions().get(i);
                validateCondition(t, r, i, c, table, problems);
            }
            if (!row.actions().keySet().equals(actionFields)) {
                problems.add(r + "动作字段集合 " + row.actions().keySet()
                        + " 与动作列定义 " + actionFields + " 不一致（必须完全一致）");
            }
            for (var ac : table.actionColumns()) {
                Object v = row.actions().get(ac.field());
                if (v != null && !literalTypeOk(ac.type(), v)) {
                    problems.add(r + "动作 " + ac.field() + " 字面量类型不匹配，期望 "
                            + ac.type() + "，实际 " + v.getClass().getSimpleName());
                }
            }
        }
    }

    private void validateCondition(String t, String r, int col, ConditionColumn c,
                                   DecisionTable table, List<String> problems) {
        String where = r + "条件列#" + col + " ";
        if (c == null || c.wildcard()) {
            return;
        }
        var schema = table.inputFields().get(c.field());
        if (schema == null) {
            problems.add(where + "引用了未声明的输入字段: " + c.field());
            return;
        }
        Operator op = c.operator();
        if (op == null) {
            problems.add(where + "操作符不能为空");
            return;
        }
        switch (op) {
            case EQ, NE, GT, GTE, LT, LTE -> {
                if (c.value() == null) {
                    problems.add(where + "操作符 " + op + " 缺少 value 参数");
                } else {
                    checkLiteralType(where, schema.type(), c.value(), problems);
                }
            }
            case IN, NOT_IN -> {
                if (c.values().isEmpty()) {
                    problems.add(where + "操作符 " + op + " 缺少非空 values 参数");
                }
                c.values().forEach(v -> checkLiteralType(where, schema.type(), v, problems));
            }
            case RANGE -> {
                if (schema.type() != DataType.NUMBER) {
                    problems.add(where + "RANGE 仅支持 NUMBER 字段: " + c.field());
                }
                if (c.lower() == null && c.upper() == null) {
                    problems.add(where + "RANGE 至少需要一个边界");
                }
                if (c.lower() != null) {
                    checkLiteralType(where, DataType.NUMBER, c.lower(), problems);
                }
                if (c.upper() != null) {
                    checkLiteralType(where, DataType.NUMBER, c.upper(), problems);
                }
                if (c.lower() != null && c.upper() != null
                        && ((Number) c.lower()).doubleValue() > ((Number) c.upper()).doubleValue()) {
                    problems.add(where + "RANGE 下界大于上界");
                }
            }
            case PRESENT -> {
            }
            default -> problems.add(where + "未知操作符: " + op);
        }
    }

    private void checkLiteralType(String where, DataType fieldType, Object literal,
                                  List<String> problems) {
        boolean ok = switch (fieldType) {
            case STRING -> literal instanceof String;
            case BOOLEAN -> literal instanceof Boolean;
            case NUMBER -> literal instanceof Number;
        };
        if (!ok) {
            problems.add(where + "字面量 " + literal + " 类型不匹配，期望 " + fieldType
                    + "，实际 " + literal.getClass().getSimpleName());
        }
    }

    private boolean literalTypeOk(DataType type, Object v) {
        return switch (type) {
            case STRING -> v instanceof String;
            case NUMBER -> v instanceof Number;
            case BOOLEAN -> v instanceof Boolean;
        };
    }

    private boolean isBlank(String s) {
        return s == null || s.isBlank();
    }
}
