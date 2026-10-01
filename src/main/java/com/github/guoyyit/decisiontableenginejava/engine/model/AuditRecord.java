package com.github.guoyyit.decisiontableenginejava.engine.model;

import java.time.Instant;

/**
 * 审计记录：谁在何时对哪个对象执行了什么操作，结果如何。
 *
 * @param id        审计流水号
 * @param timestamp 操作时间
 * @param user      操作人
 * @param action    操作类型：PUBLISH / ROLLBACK / PROMOTE / EVALUATE
 * @param target    操作对象（如 ruleSetName#version）
 * @param result    结果：SUCCESS / DENIED / FAILURE
 * @param detail    详情（失败原因、灰度比例等）
 */
public record AuditRecord(
        long id,
        Instant timestamp,
        String user,
        String action,
        String target,
        String result,
        String detail
) {
}
