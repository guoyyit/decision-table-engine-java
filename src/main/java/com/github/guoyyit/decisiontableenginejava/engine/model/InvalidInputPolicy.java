package com.github.guoyyit.decisiontableenginejava.engine.model;

/**
 * 输入不合法（缺失、类型不匹配、越界）时的处理策略。
 * <ul>
 *   <li>REJECT：整表拒绝，输出 REJECT 状态与可定位到具体规则/列的错误链。</li>
 *   <li>DEGRADE：降级处理，输出 DEGRADED 状态并应用该表配置的默认动作。</li>
 * </ul>
 */
public enum InvalidInputPolicy {
    REJECT,
    DEGRADE
}
