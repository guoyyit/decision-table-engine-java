package com.github.guoyyit.decisiontableenginejava.eval;

/**
 * 单条规则的匹配轨迹。
 *
 * @param rowId    规则行 ID
 * @param matched  是否匹配
 * @param failedColumn 未匹配/出错的条件列序号（-1 表示整体匹配）
 * @param detail   说明
 */
public record TraceEntry(String rowId, boolean matched, int failedColumn, String detail) {
}
