package com.github.guoyyit.decisiontableenginejava.engine.model;

import java.util.List;
import java.util.Map;

/**
 * 一次规则评估的完整结果。
 *
 * @param ruleSetName     规则集名
 * @param version         实际使用的规则集版本号（灰度或全量）
 * @param status          最终状态
 * @param matchedRows     命中的规则行（按表、行号确定排序）
 * @param actionsByTable  各决策表输出的动作（key 为表名，再 key 为动作列名）
 * @param errors          输入校验错误（非 REJECT 时为空列表）
 * @param conflicts       动作冲突详情（非 CONFLICT 时为空列表）
 * @param evaluatedTables 参与评估的决策表名（按声明顺序）
 * @param elapsedNanos    本次评估耗时（纳秒）
 */
public record EvaluationResult(
        String ruleSetName,
        int version,
        EvaluationStatus status,
        List<MatchedRow> matchedRows,
        Map<String, Map<String, Object>> actionsByTable,
        List<ValidationError> errors,
        List<ConflictDetail> conflicts,
        List<String> evaluatedTables,
        long elapsedNanos
) {
    /** 是否为拒绝（输入非法）。 */
    public boolean rejected() {
        return status == EvaluationStatus.REJECT;
    }

    /** 是否为降级输出。 */
    public boolean degraded() {
        return status == EvaluationStatus.DEGRADE;
    }

    /** 是否命中（含降级命中）。 */
    public boolean hit() {
        return status == EvaluationStatus.HIT || status == EvaluationStatus.DEGRADE;
    }
}
