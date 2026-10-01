package com.github.guoyyit.decisiontableenginejava.engine.model;

/**
 * 条件列支持的比较算子。
 * <ul>
 *   <li>EQ/NE：等于/不等于</li>
 *   <li>GT/GE/LT/LE：数值或可比较对象的大小比较</li>
 *   <li>IN/NOT_IN：属于/不属于给定集合</li>
 *   <li>RANGE：闭区间 [lo, hi]，单元格值为长度 2 的数组</li>
 *   <li>CONTAINS：字符串包含子串，或集合包含元素</li>
 *   <li>IS_NULL/NOT_NULL：输入字段为 null / 非 null（单元格值被忽略）</li>
 * </ul>
 */
public enum Operator {
    EQ, NE, GT, GE, LT, LE, IN, NOT_IN, RANGE, CONTAINS, IS_NULL, NOT_NULL
}
