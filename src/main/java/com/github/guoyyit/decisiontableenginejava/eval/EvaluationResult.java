package com.github.guoyyit.decisiontableenginejava.eval;

import java.util.List;
import java.util.Map;

/**
 * 一次评估的完整结果（不可变）。
 *
 * @param status       状态
 * @param tableId      决策表 ID
 * @param version      实际使用的规则版本
 * @param gray         是否来自灰度版本
 * @param outputs      动作输出
 * @param matchedRowIds 命中的行 ID（按定义顺序）
 * @param trace        匹配轨迹（按行定义顺序）
 * @param errors       输入错误列表
 * @param reason        状态补充说明
 * @param elapsedNanos  评估耗时（纳秒）
 * @param totalRows     表内规则总行数
 * @param evaluatedRows 索引预筛后实际逐列判定的行数（&lt;=totalRows；相等表示走了全表扫描）
 */
public record EvaluationResult(EvalStatus status, String tableId, long version,
                               boolean gray, Map<String, Object> outputs,
                               List<String> matchedRowIds, List<TraceEntry> trace,
                               List<InputError> errors, String reason, long elapsedNanos,
                               int totalRows, int evaluatedRows) {
    public EvaluationResult {
        outputs = outputs == null ? Map.of() : Map.copyOf(outputs);
        matchedRowIds = matchedRowIds == null ? List.of() : List.copyOf(matchedRowIds);
        trace = trace == null ? List.of() : List.copyOf(trace);
        errors = errors == null ? List.of() : List.copyOf(errors);
    }
}
