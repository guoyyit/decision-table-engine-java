package com.github.guoyyit.decisiontableenginejava.engine.model;

/**
 * 规则集校验失败异常。发布前校验不通过时抛出，
 * 服务端必须保持上一个可用版本继续服务，不得切换。
 */
public class RuleSetValidationException extends RuleEngineException {
    public RuleSetValidationException(String message) {
        super(message);
    }

    public RuleSetValidationException(String message, Throwable cause) {
        super(message, cause);
    }
}
