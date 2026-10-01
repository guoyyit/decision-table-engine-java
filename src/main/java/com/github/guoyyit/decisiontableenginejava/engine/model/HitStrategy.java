package com.github.guoyyit.decisiontableenginejava.engine.model;

/**
 * 命中策略。
 * <ul>
 *   <li>FIRST_MATCH：按优先级降序、行号升序取第一个命中行；
 *       若同一最高优先级存在多个命中行，结果标记为 DUPLICATE_HIT，
 *       动作按 TiePolicy 确定的第一个命中行执行。</li>
 *   <li>ALL_MATCH：收集全部命中行；若不同命中行对同一动作列给出不同值，
 *       结果标记为 CONFLICT；否则合并所有命中行的动作。</li>
 * </ul>
 */
public enum HitStrategy {
    FIRST_MATCH,
    ALL_MATCH
}
