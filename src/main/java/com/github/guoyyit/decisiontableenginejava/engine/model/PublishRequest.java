package com.github.guoyyit.decisiontableenginejava.engine.model;

import java.util.List;

/**
 * 规则集发布请求。版本号由服务端单调分配，调用方不可指定，
 * 以保证"评估结果必然来自某个已提交的完整版本"。
 *
 * @param ruleSetName 规则集名
 * @param tables      决策表列表
 * @param comment     发布说明
 * @param grayRatio   灰度比例 0..100，null 视为 100（全量发布）
 * @param grayKey     灰度分流字段名，null 或空表示不启用灰度
 */
public record PublishRequest(
        String ruleSetName,
        List<DecisionTable> tables,
        String comment,
        Integer grayRatio,
        String grayKey
) {
}
