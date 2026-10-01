package com.github.guoyyit.decisiontableenginejava.model;

/**
 * 审计事件。所有规则变更尝试（含被拒绝的越权尝试）都记录。
 *
 * @param at       时间（epoch millis）
 * @param actor    操作人
 * @param roles    操作人角色
 * @param action   动作类型
 * @param detail   详情
 * @param allowed  是否允许
 * @param version  关联版本（无则为 -1）
 */
public record AuditEvent(long at, String actor, String roles, String action,
                         String detail, boolean allowed, long version) {
}
