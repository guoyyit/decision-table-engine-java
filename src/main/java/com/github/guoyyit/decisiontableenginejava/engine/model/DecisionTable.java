package com.github.guoyyit.decisiontableenginejava.engine.model;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 一张决策表：包含若干条件列、动作列与规则行。
 *
 * @param name               表名，规则集内唯一
 * @param conditionColumns   条件列定义（有序）
 * @param actionColumns      动作列定义（有序）
 * @param rows               规则行（有序，行号为列表下标）
 * @param hitStrategy        命中策略，默认 FIRST_MATCH
 * @param tiePolicy          优先级相同裁决策略，默认 LOWER_ROW_INDEX_WINS
 * @param invalidInputPolicy 非法输入处理策略，默认 REJECT
 * @param degradedActions    降级时输出的默认动作（key 为动作列名）
 */
public record DecisionTable(
        String name,
        List<ConditionColumn> conditionColumns,
        List<ActionColumn> actionColumns,
        List<RuleRow> rows,
        HitStrategy hitStrategy,
        TiePolicy tiePolicy,
        InvalidInputPolicy invalidInputPolicy,
        Map<String, Object> degradedActions
) {
    public DecisionTable {
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("决策表 name 不能为空");
        }
        conditionColumns = conditionColumns == null ? List.of() : List.copyOf(conditionColumns);
        actionColumns = actionColumns == null ? List.of() : List.copyOf(actionColumns);
        rows = rows == null ? List.of() : List.copyOf(rows);
        hitStrategy = hitStrategy == null ? HitStrategy.FIRST_MATCH : hitStrategy;
        tiePolicy = tiePolicy == null ? TiePolicy.LOWER_ROW_INDEX_WINS : tiePolicy;
        invalidInputPolicy = invalidInputPolicy == null ? InvalidInputPolicy.REJECT : invalidInputPolicy;
        degradedActions = degradedActions == null ? Map.of() : new LinkedHashMap<>(degradedActions);
    }

    /** 创建默认策略的决策表。 */
    public static DecisionTable of(String name,
                                   List<ConditionColumn> conditionColumns,
                                   List<ActionColumn> actionColumns,
                                   List<RuleRow> rows) {
        return new DecisionTable(name, conditionColumns, actionColumns, rows,
                HitStrategy.FIRST_MATCH, TiePolicy.LOWER_ROW_INDEX_WINS,
                InvalidInputPolicy.REJECT, Map.of());
    }

    /** 创建可指定命中策略与非法输入策略的决策表。 */
    public static DecisionTable of(String name,
                                   List<ConditionColumn> conditionColumns,
                                   List<ActionColumn> actionColumns,
                                   List<RuleRow> rows,
                                   HitStrategy hitStrategy,
                                   InvalidInputPolicy invalidInputPolicy,
                                   Map<String, Object> degradedActions) {
        return new DecisionTable(name, conditionColumns, actionColumns, rows,
                hitStrategy, TiePolicy.LOWER_ROW_INDEX_WINS, invalidInputPolicy, degradedActions);
    }

    /**
     * 返回按优先级降序、行号升序排列的行副本（不修改原列表）。
     * 排序键为 (priority DESC, 行号 ASC)，全程确定，不依赖任何哈希遍历顺序。
     */
    public List<IndexedRow> orderedRows() {
        List<IndexedRow> sorted = new ArrayList<>(rows.size());
        for (int i = 0; i < rows.size(); i++) {
            sorted.add(new IndexedRow(rows.get(i), i));
        }
        sorted.sort((a, b) -> {
            int c = Integer.compare(b.row().priority(), a.row().priority());
            return c != 0 ? c : Integer.compare(a.index(), b.index());
        });
        return sorted;
    }

    /** 规则行与其在表中的原始行号。 */
    public record IndexedRow(RuleRow row, int index) {
    }
}
