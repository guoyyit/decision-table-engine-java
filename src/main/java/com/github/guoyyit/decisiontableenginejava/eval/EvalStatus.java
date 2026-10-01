package com.github.guoyyit.decisiontableenginejava.eval;

/**
 * 评估结果状态：
 * HIT            正常命中
 * NO_HIT         无任何规则命中（输入合法）
 * DUPLICATE_HIT  多条规则命中但动作完全一致（重复命中）
 * CONFLICT       多条规则命中且动作不一致，且裁决策略不允许（条件冲突）
 * REJECTED       输入不合法（缺失/类型/范围），拒绝执行
 * DEGRADED       输入不合法但策略允许降级，返回降级结果
 */
public enum EvalStatus {
    HIT, NO_HIT, DUPLICATE_HIT, CONFLICT, REJECTED, DEGRADED
}
