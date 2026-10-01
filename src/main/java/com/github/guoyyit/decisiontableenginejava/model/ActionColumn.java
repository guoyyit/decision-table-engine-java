package com.github.guoyyit.decisiontableenginejava.model;

/**
 * 动作列定义。
 *
 * @param field 输出字段名
 * @param type  输出值类型
 */
public record ActionColumn(String field, DataType type) {
}
