package com.github.guoyyit.decisiontableenginejava.engine.model;

/**
 * 操作角色。
 */
public enum Role {
    /** 仅可评估与查询。 */
    VIEWER,
    /** 可发布规则版本。 */
    PUBLISHER,
    /** 可回滚、提升灰度、管理规则。 */
    ADMIN
}
