package com.github.guoyyit.decisiontableenginejava.web;

import com.github.guoyyit.decisiontableenginejava.model.DecisionTable;

import java.util.List;

/** 提交新版本请求。 */
public record CommitRequest(String message, List<DecisionTable> tables) {
}
