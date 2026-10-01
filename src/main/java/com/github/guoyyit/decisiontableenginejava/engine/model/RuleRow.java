package com.github.guoyyit.decisiontableenginejava.engine.model;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 决策表中的一行规则。
 *
 * @param id         行标识，表内唯一
 * @param priority   优先级，数值越大越优先
 * @param conditions 条件单元格：key 为条件列名，value 为单元格值；
 *                   value 为 null 或缺席表示该列对本行不生效（通配）
 * @param actions    动作单元格：key 为动作列名，value 为命中后输出的动作值
 */
public record RuleRow(
        String id,
        int priority,
        Map<String, Object> conditions,
        Map<String, Object> actions
) {
    public RuleRow {
        if (id == null || id.isBlank()) {
            throw new IllegalArgumentException("规则行 id 不能为空");
        }
        // 使用 LinkedHashMap 保留声明顺序，行号即列表下标，是优先级相同时的确定裁决依据
        conditions = conditions == null ? new LinkedHashMap<>() : new LinkedHashMap<>(conditions);
        actions = actions == null ? new LinkedHashMap<>() : new LinkedHashMap<>(actions);
    }

    /** 便捷工厂：条件与动作以可变 Map 方式传入。 */
    public static RuleRow of(String id, int priority, Map<String, Object> conditions, Map<String, Object> actions) {
        return new RuleRow(id, priority, conditions, actions);
    }
}
