package com.github.guoyyit.decisiontableenginejava.engine.model;

import java.time.Instant;
import java.util.List;

/**
 * 规则集的一个不可变版本。
 * <p>一旦创建，tables 内容不可变更；每次发布都会产生一个新版本，
 * 评估始终读取某个已提交的完整版本，不存在字段级混合。</p>
 *
 * @param name      规则集名称
 * @param version   版本号，单调递增
 * @param status    版本状态
 * @param tables    决策表列表（有序）
 * @param createdBy 发布人
 * @param createdAt  发布时间
 * @param comment   发布说明
 * @param grayRatio 灰度流量比例（0..100），100 表示全量
 * @param grayKey   灰度分流依据的输入字段名
 */
public record RuleSet(
        String name,
        int version,
        RuleSetStatus status,
        List<DecisionTable> tables,
        String createdBy,
        Instant createdAt,
        String comment,
        int grayRatio,
        String grayKey
) {
    public RuleSet {
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("规则集 name 不能为空");
        }
        if (version <= 0) {
            throw new IllegalArgumentException("规则集 version 必须为正整数: " + version);
        }
        if (grayRatio < 0 || grayRatio > 100) {
            throw new IllegalArgumentException("grayRatio 必须在 0..100 之间: " + grayRatio);
        }
        tables = tables == null ? List.of() : List.copyOf(tables);
        status = status == null ? RuleSetStatus.INACTIVE : status;
        createdAt = createdAt == null ? Instant.now() : createdAt;
        comment = comment == null ? "" : comment;
        grayKey = grayKey == null ? "" : grayKey;
    }

    /** 创建一个新版本（状态由发布流程决定）。 */
    public static RuleSet of(String name, int version, List<DecisionTable> tables,
                              String createdBy, String comment, int grayRatio, String grayKey) {
        return new RuleSet(name, version, RuleSetStatus.INACTIVE, tables,
                createdBy, Instant.now(), comment, grayRatio, grayKey);
    }

    /** 返回内容相同但版本号/状态不同的副本（回滚即复制历史版本内容为新版本）。 */
    public RuleSet withVersion(int newVersion, RuleSetStatus newStatus) {
        return new RuleSet(name, newVersion, newStatus, tables, createdBy, Instant.now(),
                "rollback from v" + version, grayRatio, grayKey);
    }

    /** 返回内容相同但状态不同的副本。 */
    public RuleSet withStatus(RuleSetStatus newStatus) {
        return new RuleSet(name, version, newStatus, tables, createdBy, createdAt,
                comment, grayRatio, grayKey);
    }
}
