package com.github.guoyyit.decisiontableenginejava.engine.model;

/**
 * 规则集版本状态。
 * <ul>
 *   <li>ACTIVE：当前全量生效版本（灰度比例 100%）。</li>
 *   <li>GRAY：灰度版本，按稳定哈希分流部分流量。</li>
 *   <li>INACTIVE：历史版本，不可直接提供服务，只能通过回滚激活。</li>
 * </ul>
 */
public enum RuleSetStatus {
    ACTIVE,
    GRAY,
    INACTIVE
}
