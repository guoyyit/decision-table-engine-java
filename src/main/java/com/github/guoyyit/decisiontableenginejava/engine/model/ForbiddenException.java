package com.github.guoyyit.decisiontableenginejava.engine.model;

/**
 * 越权操作异常：当前用户角色无权执行该操作。
 */
public class ForbiddenException extends RuleEngineException {
    public ForbiddenException(String message) {
        super(message);
    }
}
