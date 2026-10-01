package com.github.guoyyit.decisiontableenginejava.engine.model;

/**
 * 命中的规则行信息。
 *
 * @param tableName 所在决策表名
 * @param rowId     规则行 id
 * @param priority   该行优先级
 */
public record MatchedRow(
        String tableName,
        String rowId,
        int priority
) {
}
