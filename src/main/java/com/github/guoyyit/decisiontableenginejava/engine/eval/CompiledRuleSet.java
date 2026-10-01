package com.github.guoyyit.decisiontableenginejava.engine.eval;

import com.github.guoyyit.decisiontableenginejava.engine.model.RuleSet;

import java.util.List;
import java.util.Map;

/**
 * 预编译后的规则集：在 RuleSet 不可变版本之上附加每张表的等值索引，
 * 评估时通过索引定位候选行，避免全表扫描。
 *
 * @param ruleSet        原始规则集版本（不可变）
 * @param tableIndexes   每张表的等值索引：
 *                       key = 条件列名，value = (单元格值 -> 命中该值的行号列表，行号升序)
 * @param unindexedRows  每张表无法建立等值索引的行号列表（无 EQ 条件的行），评估时始终纳入候选
 */
public record CompiledRuleSet(
        RuleSet ruleSet,
        Map<String, Map<String, Map<Object, List<Integer>>>> tableIndexes,
        Map<String, List<Integer>> unindexedRows
) {
    public int version() {
        return ruleSet.version();
    }
}
