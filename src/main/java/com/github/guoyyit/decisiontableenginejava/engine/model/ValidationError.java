package com.github.guoyyit.decisiontableenginejava.engine.model;

/**
 * 输入校验错误，可定位到具体规则集版本下的表、列与规则行。
 *
 * @param tableName  决策表名
 * @param columnName 条件列名
 * @param rowId      关联的规则行 id（缺失类错误可能为 null）
 * @param field      输入字段名
 * @param code       错误码：MISSING_FIELD / TYPE_MISMATCH / OUT_OF_RANGE / ENUM_VIOLATION
 * @param message    可读的错误说明
 */
public record ValidationError(
        String tableName,
        String columnName,
        String rowId,
        String field,
        String code,
        String message
) {
}
