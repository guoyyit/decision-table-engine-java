package com.github.guoyyit.decisiontableenginejava.web;

import java.util.List;

/** 统一错误响应。 */
public record ApiError(String error, String message, List<String> details, int status) {
    public ApiError(String error, String message, int status) {
        this(error, message, List.of(), status);
    }
}
