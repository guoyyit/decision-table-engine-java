package com.github.guoyyit.decisiontableenginejava.engine.model;

import java.util.List;

/**
 * 决策表条件列定义。
 *
 * @param name          列名（表内唯一），规则行通过该列名引用
 * @param field         对应输入字段名（默认与列名相同）
 * @param type          字段值类型
 * @param operator      比较算子
 * @param required      是否必填；必填列缺失时直接拒绝
 * @param min           数值型输入允许的最小值（含边界），null 表示不校验
 * @param max           数值型输入允许的最大值（含边界），null 表示不校验
 * @param allowedValues ENUM 类型允许的取值集合
 */
public record ConditionColumn(
        String name,
        String field,
        ColumnType type,
        Operator operator,
        boolean required,
        Double min,
        Double max,
        List<Object> allowedValues
) {
    public ConditionColumn {
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("条件列 name 不能为空");
        }
        if (field == null || field.isBlank()) {
            throw new IllegalArgumentException("条件列 field 不能为空: " + name);
        }
        if (type == null) {
            throw new IllegalArgumentException("条件列 type 不能为空: " + name);
        }
        if (operator == null) {
            throw new IllegalArgumentException("条件列 operator 不能为空: " + name);
        }
        allowedValues = allowedValues == null ? List.of() : List.copyOf(allowedValues);
    }

    /** 创建必填条件列（无取值范围约束）。 */
    public static ConditionColumn of(String name, String field, ColumnType type, Operator operator) {
        return new ConditionColumn(name, field, type, operator, true, null, null, List.of());
    }

    /** 创建条件列并指定是否必填。 */
    public static ConditionColumn of(String name, String field, ColumnType type, Operator operator, boolean required) {
        return new ConditionColumn(name, field, type, operator, required, null, null, List.of());
    }

    /** 附加数值范围约束（含边界）。 */
    public ConditionColumn withRange(double min, double max) {
        return new ConditionColumn(name, field, type, operator, required, min, max, allowedValues);
    }

    /** 附加枚举允许值。 */
    public ConditionColumn withAllowedValues(List<Object> allowed) {
        return new ConditionColumn(name, field, type, operator, required, min, max, List.copyOf(allowed));
    }
}
