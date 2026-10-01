package com.github.guoyyit.decisiontableenginejava.model;

import java.util.List;
import java.util.Map;

/**
 * 决策表中的一行规则。
 *
 * @param rowId      行 ID（表内唯一，用于错误定位）
 * @param priority   优先级，数值越小优先级越高
 * @param conditions 按列顺序排列的条件（null 元素等价通配）
 * @param actions    动作列字段名到字面量输出值的映射
 */
public record RuleRow(String rowId, int priority,
                      List<ConditionColumn> conditions,
                      Map<String, Object> actions) {
    public RuleRow {
        conditions = conditions == null ? List.of() : List.copyOf(conditions);
        actions = actions == null ? Map.of() : Map.copyOf(actions);
    }
}
