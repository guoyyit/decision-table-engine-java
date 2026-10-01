package com.github.guoyyit.decisiontableenginejava.engine.eval;

import com.github.guoyyit.decisiontableenginejava.engine.model.ActionColumn;
import com.github.guoyyit.decisiontableenginejava.engine.model.ColumnType;
import com.github.guoyyit.decisiontableenginejava.engine.model.ConditionColumn;
import com.github.guoyyit.decisiontableenginejava.engine.model.DecisionTable;
import com.github.guoyyit.decisiontableenginejava.engine.model.Operator;
import com.github.guoyyit.decisiontableenginejava.engine.model.RuleRow;
import com.github.guoyyit.decisiontableenginejava.engine.model.RuleSet;
import com.github.guoyyit.decisiontableenginejava.engine.model.RuleSetValidationException;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 规则集编译器：发布前对规则集做结构校验并预编译索引。
 * <p>校验不通过抛出 {@link RuleSetValidationException}，调用方必须保证
 * 不切换当前可用版本（热更新失败时继续服务旧版本）。</p>
 */
@Component
public class RuleSetCompiler {

    /**
     * 校验并编译一个规则集版本。
     *
     * @param ruleSet 待发布的规则集（版本号已分配）
     * @return 预编译规则集（含等值索引）
     */
    public CompiledRuleSet compile(RuleSet ruleSet) {
        if (ruleSet.tables().isEmpty()) {
            throw new RuleSetValidationException("规则集至少需要一张决策表: " + ruleSet.name());
        }

        Map<String, Map<String, Map<Object, List<Integer>>>> tableIndexes = new LinkedHashMap<>();
        Map<String, List<Integer>> unindexedRows = new LinkedHashMap<>();

        for (DecisionTable table : ruleSet.tables()) {
            validateTable(table);
            Map<String, Map<Object, List<Integer>>> columnIndex = new LinkedHashMap<>();
            List<Integer> unindexed = new ArrayList<>();

            for (int rowIdx = 0; rowIdx < table.rows().size(); rowIdx++) {
                RuleRow row = table.rows().get(rowIdx);
                boolean indexed = false;
                for (ConditionColumn col : table.conditionColumns()) {
                    Object cell = row.conditions().get(col.name());
                    if (cell == null) {
                        continue;
                    }
                    checkCell(table, col, row, cell);
                    if (col.operator() == Operator.EQ) {
                        columnIndex
                                .computeIfAbsent(col.name(), k -> new LinkedHashMap<>())
                                .computeIfAbsent(cell, k -> new ArrayList<>())
                                .add(rowIdx);
                        indexed = true;
                    }
                }
                if (!indexed) {
                    unindexed.add(rowIdx);
                }
                for (ActionColumn act : table.actionColumns()) {
                    Object cell = row.actions().get(act.name());
                    if (cell != null) {
                        checkActionValue(table, act, row, cell);
                    }
                }
            }
            tableIndexes.put(table.name(), columnIndex);
            unindexedRows.put(table.name(), unindexed);
        }
        return new CompiledRuleSet(ruleSet, tableIndexes, unindexedRows);
    }

    private void validateTable(DecisionTable table) {
        if (table.conditionColumns().isEmpty()) {
            throw new RuleSetValidationException("决策表至少需要一个条件列: " + table.name());
        }
        Set<String> condNames = new LinkedHashSet<>();
        for (ConditionColumn col : table.conditionColumns()) {
            if (!condNames.add(col.name())) {
                throw new RuleSetValidationException(
                        "决策表 " + table.name() + " 条件列名重复: " + col.name());
            }
        }
        Set<String> actNames = new LinkedHashSet<>();
        for (ActionColumn col : table.actionColumns()) {
            if (!actNames.add(col.name())) {
                throw new RuleSetValidationException(
                        "决策表 " + table.name() + " 动作列名重复: " + col.name());
            }
        }
        Set<String> rowIds = new LinkedHashSet<>();
        for (RuleRow row : table.rows()) {
            if (!rowIds.add(row.id())) {
                throw new RuleSetValidationException(
                        "决策表 " + table.name() + " 规则行 id 重复: " + row.id());
            }
            for (String condKey : row.conditions().keySet()) {
                if (!condNames.contains(condKey)) {
                    throw new RuleSetValidationException(
                            "决策表 " + table.name() + " 行 " + row.id()
                                    + " 引用了不存在的条件列: " + condKey);
                }
            }
            for (String actKey : row.actions().keySet()) {
                if (!actNames.contains(actKey)) {
                    throw new RuleSetValidationException(
                            "决策表 " + table.name() + " 行 " + row.id()
                                    + " 引用了不存在的动作列: " + actKey);
                }
            }
        }
        for (String actKey : table.degradedActions().keySet()) {
            if (!actNames.contains(actKey)) {
                throw new RuleSetValidationException(
                        "决策表 " + table.name() + " 降级动作引用了不存在的动作列: " + actKey);
            }
        }
    }

    /** 校验条件单元格值与列类型、算子匹配。 */
    @SuppressWarnings("unchecked")
    private void checkCell(DecisionTable table, ConditionColumn col, RuleRow row, Object cell) {
        ColumnType type = col.type();
        Operator op = col.operator();
        String location = "表 " + table.name() + " 列 " + col.name() + " 行 " + row.id();

        switch (op) {
            case EQ, NE, GT, GE, LT, LE -> {
                if (!isTypeMatch(type, cell)) {
                    throw new RuleSetValidationException(
                            location + " 的单元格值类型应为 " + type + "，实际为 "
                                    + cell.getClass().getSimpleName());
                }
            }
            case IN, NOT_IN -> {
                if (!(cell instanceof Iterable<?> iterable)) {
                    throw new RuleSetValidationException(
                            location + " 的 IN/NOT_IN 单元格必须是集合");
                }
                for (Object item : iterable) {
                    if (!isTypeMatch(type, item)) {
                        throw new RuleSetValidationException(
                                location + " 的 IN/NOT_IN 元素类型应为 " + type + "，实际为 "
                                        + (item == null ? "null" : item.getClass().getSimpleName()));
                    }
                }
            }
            case RANGE -> {
                if (!(cell instanceof List<?> list) || list.size() != 2) {
                    throw new RuleSetValidationException(
                            location + " 的 RANGE 单元格必须是长度为 2 的数组 [lo, hi]");
                }
                if (!isTypeMatch(type, list.get(0)) || !isTypeMatch(type, list.get(1))) {
                    throw new RuleSetValidationException(
                            location + " 的 RANGE 边界值类型应为 " + type);
                }
            }
            case CONTAINS -> {
                if (type == ColumnType.STRING && !(cell instanceof String)) {
                    throw new RuleSetValidationException(location + " 的 CONTAINS 单元格必须是字符串");
                }
            }
            case IS_NULL, NOT_NULL -> {
                // 单元格值被忽略，允许任意非 null 占位
            }
        }
    }

    private void checkActionValue(DecisionTable table, ActionColumn act, RuleRow row, Object cell) {
        if (!isTypeMatch(act.type(), cell)) {
            throw new RuleSetValidationException(
                    "表 " + table.name() + " 动作列 " + act.name() + " 行 " + row.id()
                            + " 的动作值类型应为 " + act.type() + "，实际为 "
                            + cell.getClass().getSimpleName());
        }
    }

    private boolean isTypeMatch(ColumnType type, Object value) {
        if (value == null) {
            return false;
        }
        return switch (type) {
            case STRING -> value instanceof String;
            case NUMBER -> value instanceof Number;
            case BOOLEAN -> value instanceof Boolean;
            case ENUM -> value instanceof String;
        };
    }
}
