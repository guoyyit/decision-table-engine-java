package com.github.guoyyit.decisiontableenginejava.model;

import java.util.List;
import java.util.Map;

/**
 * 一张决策表。
 *
 * @param tableId      表 ID（版本内唯一）
 * @param name         表名
 * @param hitPolicy    命中策略
 * @param tiePolicy    同优先级裁决策略
 * @param inputFields  输入字段模式（字段名到 schema）
 * @param actionColumns 动作列定义
 * @param rows         规则行；定义顺序即为稳定次序，也是 FIRST 策略的判定次序
 */
public record DecisionTable(String tableId, String name,
                            HitPolicy hitPolicy, TiePolicy tiePolicy,
                            Map<String, FieldSchema> inputFields,
                            List<ActionColumn> actionColumns,
                            List<RuleRow> rows) {
    public DecisionTable {
        inputFields = inputFields == null ? Map.of() : Map.copyOf(inputFields);
        actionColumns = actionColumns == null ? List.of() : List.copyOf(actionColumns);
        rows = rows == null ? List.of() : List.copyOf(rows);
    }
}
