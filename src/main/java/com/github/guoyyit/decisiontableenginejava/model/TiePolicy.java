package com.github.guoyyit.decisiontableenginejava.model;

/** 同优先级裁决策略（PRIORITY 命中策略下多条规则并列时使用）。 */
public enum TiePolicy {
    CONFLICT, LOWEST_ID, MERGE
}
