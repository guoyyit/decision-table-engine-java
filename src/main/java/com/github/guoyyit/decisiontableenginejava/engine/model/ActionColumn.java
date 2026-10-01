package com.github.guoyyit.decisiontableenginejava.engine.model;

/**
 * 决策表动作列定义。
 *
 * @param name         动作列名（表内唯一），规则行通过该列名给出动作值
 * @param type         动作值类型
 * @param defaultValue 未命中或降级时使用的默认值，可为 null
 */
public record ActionColumn(
        String name,
        ColumnType type,
        Object defaultValue
) {
    public ActionColumn {
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("动作列 name 不能为空");
        }
        if (type == null) {
            throw new IllegalArgumentException("动作列 type 不能为空: " + name);
        }
    }

    /** 创建无默认值的动作列。 */
    public static ActionColumn of(String name, ColumnType type) {
        return new ActionColumn(name, type, null);
    }

    /** 创建带默认值的动作列。 */
    public static ActionColumn of(String name, ColumnType type, Object defaultValue) {
        return new ActionColumn(name, type, defaultValue);
    }
}
