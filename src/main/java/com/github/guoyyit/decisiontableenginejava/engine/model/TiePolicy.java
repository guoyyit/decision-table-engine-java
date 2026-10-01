package com.github.guoyyit.decisiontableenginejava.engine.model;

/**
 * 优先级相同时的裁决策略。
 * <p>LOWER_ROW_INDEX_WINS：优先级相同时，行号（在表中的声明顺序）更小的行胜出。
 * 该策略不依赖任何集合遍历顺序，结果确定可复现。</p>
 */
public enum TiePolicy {
    LOWER_ROW_INDEX_WINS
}
