package com.github.guoyyit.decisiontableenginejava.engine.model;

import java.util.List;

/**
 * 动作冲突详情：ALL_MATCH 策略下，多个命中行对同一动作列给出了不同值。
 *
 * @param tableName    决策表名
 * @param actionColumn 动作列名
 * @param rowIds       参与冲突的规则行 id（按行号升序）
 * @param values       各自行给出的动作值（与 rowIds 一一对应）
 */
public record ConflictDetail(
        String tableName,
        String actionColumn,
        List<String> rowIds,
        List<Object> values
) {
}
