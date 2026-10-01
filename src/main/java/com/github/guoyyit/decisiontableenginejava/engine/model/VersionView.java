package com.github.guoyyit.decisiontableenginejava.engine.model;

import java.time.Instant;

/**
 * 规则集版本视图（列表查询用）。
 */
public record VersionView(
        int version,
        String name,
        RuleSetStatus status,
        String createdBy,
        Instant createdAt,
        String comment,
        int grayRatio,
        String grayKey,
        int tableCount,
        int rowCount
) {
}
