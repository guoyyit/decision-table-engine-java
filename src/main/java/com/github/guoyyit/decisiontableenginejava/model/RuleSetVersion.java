package com.github.guoyyit.decisiontableenginejava.model;

import java.util.List;

/**
 * 规则集的一个不可变完整版本。一旦提交，内容永不变更。
 *
 * @param version 版本号（单调递增）
 * @param tables  本版本包含的全部决策表
 * @param message 提交说明
 * @param author  提交人
 * @param committedAt 提交时间（epoch millis）
 */
public record RuleSetVersion(long version, List<DecisionTable> tables,
                             String message, String author, long committedAt) {
    public RuleSetVersion {
        tables = tables == null ? List.of() : List.copyOf(tables);
    }
}
