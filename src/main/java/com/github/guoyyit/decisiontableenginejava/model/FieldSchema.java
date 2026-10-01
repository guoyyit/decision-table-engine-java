package com.github.guoyyit.decisiontableenginejava.model;

import java.util.List;

/**
 * 输入/输出字段的模式定义。
 *
 * @param name     字段名
 * @param type     类型
 * @param required 是否必填（缺失时拒绝）
 * @param min      NUMBER 类型最小值（null 表示不限）
 * @param max      NUMBER 类型最大值（null 表示不限）
 * @param allowed  STRING 类型的允许取值（null 表示不限）
 */
public record FieldSchema(String name, DataType type, boolean required,
                          Double min, Double max, List<String> allowed) {
    public FieldSchema {
        allowed = allowed == null ? List.of() : List.copyOf(allowed);
    }
}
