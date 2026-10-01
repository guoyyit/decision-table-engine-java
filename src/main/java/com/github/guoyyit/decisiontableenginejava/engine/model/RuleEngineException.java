package com.github.guoyyit.decisiontableenginejava.engine.model;

/**
 * 规则引擎运行时异常基类。
 */
public class RuleEngineException extends RuntimeException {
    public RuleEngineException(String message) {
        super(message);
    }

    public RuleEngineException(String message, Throwable cause) {
        super(message, cause);
    }
}
