package com.github.guoyyit.decisiontableenginejava.service;

/** 输入不合法时的处理策略。 */
public enum InputPolicy {
    REJECT,   // 拒绝（默认）：返回 REJECTED
    DEGRADE   // 降级：返回 DEGRADED + 默认输出
}
