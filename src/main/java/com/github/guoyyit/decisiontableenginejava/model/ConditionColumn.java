package com.github.guoyyit.decisiontableenginejava.model;

import java.util.List;

/**
 * 条件列定义。
 *
 * @param field     引用的输入字段
 * @param operator  操作符
 * @param value     单值参数（EQ/NE/GT/GTE/LT/LTE）
 * @param values    多值参数（IN/NOT_IN）
 * @param lower     RANGE 下界（null 表示开区间负无穷）
 * @param upper     RANGE 上界（null 表示开区间正无穷）
 * @param lowerIncl RANGE 是否包含下界
 * @param upperIncl RANGE 是否包含上界
 * @param wildcard  true 表示通配（"-"），任意取值（含 null）均满足
 */
public record ConditionColumn(String field, Operator operator,
                              Object value, List<Object> values,
                              Object lower, Object upper,
                              boolean lowerIncl, boolean upperIncl,
                              boolean wildcard) {
    public ConditionColumn {
        values = values == null ? List.of() : List.copyOf(values);
    }
}
