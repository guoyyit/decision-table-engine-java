package com.github.guoyyit.decisiontableenginejava.eval;

/**
 * 输入校验错误，可定位到字段。
 *
 * @param field   出错字段
 * @param reason  原因分类（MISSING/TYPE_MISMATCH/OUT_OF_RANGE/ENUM_VIOLATION/UNKNOWN_FIELD）
 * @param message 人类可读说明
 * @param tableId 关联决策表
 */
public record InputError(String tableId, String field, String reason, String message) {
}
