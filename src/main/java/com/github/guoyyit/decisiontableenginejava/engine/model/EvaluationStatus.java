package com.github.guoyyit.decisiontableenginejava.engine.model;

/**
 * 单次评估的最终状态。
 */
public enum EvaluationStatus {
    /** 有且仅有确定的命中（FIRST_MATCH 下唯一最高优先级命中，或 ALL_MATCH 下动作一致）。 */
    HIT,
    /** 没有任何规则行命中。 */
    NOT_HIT,
    /** FIRST_MATCH 下同一最高优先级存在多个命中行（条件重叠）。 */
    DUPLICATE_HIT,
    /** ALL_MATCH 下多个命中行对同一动作列给出了不同的值。 */
    CONFLICT,
    /** 输入不合法且策略为 REJECT。 */
    REJECT,
    /** 输入不合法但策略为 DEGRADE，已应用默认动作。 */
    DEGRADE
}
