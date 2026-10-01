package com.github.guoyyit.decisiontableenginejava.eval;

import com.github.guoyyit.decisiontableenginejava.engine.CompiledTable;
import com.github.guoyyit.decisiontableenginejava.model.DecisionTable;
import com.github.guoyyit.decisiontableenginejava.model.RuleRow;
import com.github.guoyyit.decisiontableenginejava.model.TiePolicy;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.TreeSet;

/**
 * 单表评估器（无状态）。裁决规则全部文档化且确定性：
 *
 * 1. FIRST：按行定义顺序，第一条命中即返回；其余命中被忽略（不产生冲突）。
 * 2. UNIQUE：有且仅有一条命中才正常；0 条 -> NO_HIT；多条 -> CONFLICT。
 * 3. PRIORITY：按 priority（数值小者优先）取最高优先级的命中行；
 *    若并列多行：
 *      - 动作完全一致 -> DUPLICATE_HIT（输出该动作）
 *      - 动作不一致 -> 依 tiePolicy：
 *          CONFLICT   -> CONFLICT（默认，保守裁决）
 *          LOWEST_ID  -> 并列行中 rowId 字典序最小者胜出，状态 HIT
 *          MERGE      -> 逐动作列合并：列值一致取该值，不一致该列置 null，
 *                        状态 DUPLICATE_HIT（降级式合并，调用方可识别）
 * 4. COLLECT：收集所有命中行的动作：
 *      - 0 条 -> NO_HIT；1 条 -> HIT
 *      - 多条且动作完全一致 -> DUPLICATE_HIT
 *      - 多条且不一致 -> outputs 为每个动作字段 -> List 值（按 rowId 排序），HIT
 *
 * trace 只包含索引预筛后实际参与逐列判定的候选行；被索引排除的行数可通过
 * totalRows/evaluatedRows 观测（evaluatedRows&lt;totalRows 说明索引生效）。
 *
 * 确定性保证：所有排序均有主键（priority、rowId），不依赖 HashMap/HashSet 遍历顺序。
 */
@Component
public class TableEvaluator {

    private final InputValidator inputValidator;

    public TableEvaluator(InputValidator inputValidator) {
        this.inputValidator = inputValidator;
    }

    public EvaluationResult evaluate(String tableId, CompiledTable compiled,
                                     Map<String, Object> input,
                                     long version, boolean gray,
                                     boolean rejectOnInputError,
                                     Map<String, Object> degradedDefaults) {
        long start = System.nanoTime();
        DecisionTable table = compiled.table();
        int totalRows = table.rows().size();

        List<InputError> errors =
                inputValidator.validate(tableId, table.inputFields(), input);
        if (!errors.isEmpty()) {
            if (rejectOnInputError) {
                return build(EvalStatus.REJECTED, tableId, version, gray,
                        Map.of(), List.of(), List.of(), errors,
                        "输入校验未通过，拒绝执行", start, totalRows, 0);
            }
            return build(EvalStatus.DEGRADED, tableId, version, gray,
                    new TreeMap<>(degradedDefaults), List.of(), List.of(), errors,
                    "输入校验未通过，按降级策略返回默认输出", start, totalRows, 0);
        }

        List<RuleRow> candidates = compiled.candidateRows(input);
        List<RuleRow> matched = compiled.matchRows(candidates, input);
        List<TraceEntry> trace = buildTrace(table, input, candidates, matched);
        int evaluated = candidates.size();

        if (matched.isEmpty()) {
            int filtered = totalRows - evaluated;
            String reason = filtered > 0
                    ? "无命中：" + filtered + " 行被索引预筛排除，其余 "
                      + evaluated + " 行逐列判定均不满足"
                    : "无任何规则命中（全表 " + totalRows + " 行逐列判定）";
            return build(EvalStatus.NO_HIT, tableId, version, gray,
                    Map.of(), List.of(), trace, List.of(), reason,
                    start, totalRows, evaluated);
        }

        return switch (table.hitPolicy()) {
            case FIRST -> doFirst(tableId, version, gray, matched, trace, start,
                    totalRows, evaluated);
            case UNIQUE -> doUnique(tableId, version, gray, matched, trace, start,
                    totalRows, evaluated);
            case PRIORITY -> doPriority(table, tableId, version, gray, matched, trace,
                    start, totalRows, evaluated);
            case COLLECT -> doCollect(tableId, version, gray, matched, trace, start,
                    totalRows, evaluated);
        };
    }

    private EvaluationResult doFirst(String tableId, long version, boolean gray,
                                     List<RuleRow> matched, List<TraceEntry> trace,
                                     long start, int total, int evaluated) {
        RuleRow first = matched.get(0);
        return build(EvalStatus.HIT, tableId, version, gray,
                new TreeMap<>(first.actions()), List.of(first.rowId()), trace, List.of(),
                "FIRST 策略取定义顺序第一条命中", start, total, evaluated);
    }

    private EvaluationResult doUnique(String tableId, long version, boolean gray,
                                      List<RuleRow> matched, List<TraceEntry> trace,
                                      long start, int total, int evaluated) {
        if (matched.size() == 1) {
            RuleRow only = matched.get(0);
            return build(EvalStatus.HIT, tableId, version, gray,
                    new TreeMap<>(only.actions()), List.of(only.rowId()), trace, List.of(),
                    "UNIQUE 策略恰好一条命中", start, total, evaluated);
        }
        List<String> ids = ids(matched);
        return build(EvalStatus.CONFLICT, tableId, version, gray,
                Map.of(), ids, trace, List.of(),
                "UNIQUE 策略要求恰好一条命中，实际命中 " + matched.size() + " 条: " + ids,
                start, total, evaluated);
    }

    private EvaluationResult doPriority(DecisionTable table, String tableId, long version,
                                        boolean gray, List<RuleRow> matched,
                                        List<TraceEntry> trace, long start,
                                        int total, int evaluated) {
        int top = matched.stream().mapToInt(RuleRow::priority).min().orElseThrow();
        List<RuleRow> topRows = matched.stream()
                .filter(r -> r.priority() == top)
                .sorted((a, b) -> a.rowId().compareTo(b.rowId()))
                .toList();
        List<String> ids = ids(topRows);

        if (topRows.size() == 1) {
            return build(EvalStatus.HIT, tableId, version, gray,
                    new TreeMap<>(topRows.get(0).actions()), ids, trace, List.of(),
                    "PRIORITY 策略取最高优先级（priority=" + top + "）唯一行",
                    start, total, evaluated);
        }
        if (sameActions(topRows)) {
            return build(EvalStatus.DUPLICATE_HIT, tableId, version, gray,
                    new TreeMap<>(topRows.get(0).actions()), ids, trace, List.of(),
                    "同优先级多行命中且动作完全一致，视为重复命中",
                    start, total, evaluated);
        }
        TiePolicy tie = table.tiePolicy();
        return switch (tie) {
            case CONFLICT -> build(EvalStatus.CONFLICT, tableId, version, gray,
                    Map.of(), ids, trace, List.of(),
                    "同优先级多行命中且动作不一致，tiePolicy=CONFLICT，裁决为冲突",
                    start, total, evaluated);
            case LOWEST_ID -> {
                RuleRow winner = topRows.get(0);
                yield build(EvalStatus.HIT, tableId, version, gray,
                        new TreeMap<>(winner.actions()), ids, trace, List.of(),
                        "同优先级冲突按 tiePolicy=LOWEST_ID 裁决，胜出行=" + winner.rowId(),
                        start, total, evaluated);
            }
            case MERGE -> {
                TreeMap<String, Object> merged = new TreeMap<>();
                for (String field : actionFields(table)) {
                    Object common = topRows.get(0).actions().get(field);
                    boolean conflictCol = false;
                    for (RuleRow r : topRows) {
                        if (!java.util.Objects.equals(common, r.actions().get(field))) {
                            conflictCol = true;
                            break;
                        }
                    }
                    merged.put(field, conflictCol ? null : common);
                }
                yield build(EvalStatus.DUPLICATE_HIT, tableId, version, gray,
                        merged, ids, trace, List.of(),
                        "同优先级冲突按 tiePolicy=MERGE 合并，不一致列置 null",
                        start, total, evaluated);
            }
        };
    }

    private EvaluationResult doCollect(String tableId, long version, boolean gray,
                                       List<RuleRow> matched, List<TraceEntry> trace,
                                       long start, int total, int evaluated) {
        List<RuleRow> sorted = matched.stream()
                .sorted((a, b) -> a.rowId().compareTo(b.rowId()))
                .toList();
        if (sorted.size() == 1) {
            return build(EvalStatus.HIT, tableId, version, gray,
                    new TreeMap<>(sorted.get(0).actions()), ids(sorted), trace, List.of(),
                    "COLLECT 策略收集到唯一命中", start, total, evaluated);
        }
        if (sameActions(sorted)) {
            return build(EvalStatus.DUPLICATE_HIT, tableId, version, gray,
                    new TreeMap<>(sorted.get(0).actions()), ids(sorted), trace, List.of(),
                    "COLLECT 多行命中且动作完全一致，重复命中", start, total, evaluated);
        }
        TreeMap<String, Object> collected = new TreeMap<>();
        for (String field : sorted.get(0).actions().keySet()) {
            List<Object> vals = new ArrayList<>();
            for (RuleRow r : sorted) {
                vals.add(r.actions().get(field));
            }
            collected.put(field, List.copyOf(vals));
        }
        return build(EvalStatus.HIT, tableId, version, gray,
                collected, ids(sorted), trace, List.of(),
                "COLLECT 策略收集多行动作，字段值以有序 List 输出",
                start, total, evaluated);
    }

    /**
     * 为候选行构建轨迹（按候选顺序，即定义顺序）。命中行标记匹配；
     * 未命中间记录第一个不满足的条件列序号，便于定位。
     */
    private List<TraceEntry> buildTrace(DecisionTable table, Map<String, Object> input,
                                        List<RuleRow> candidates, List<RuleRow> matched) {
        TreeSet<String> hitIds = new TreeSet<>(matched.stream().map(RuleRow::rowId).toList());
        List<TraceEntry> trace = new ArrayList<>();
        for (RuleRow row : candidates) {
            if (hitIds.contains(row.rowId())) {
                trace.add(new TraceEntry(row.rowId(), true, -1, "全部条件满足"));
                continue;
            }
            int failed = -1;
            for (int col = 0; col < row.conditions().size(); col++) {
                var c = row.conditions().get(col);
                Object v = c == null ? null : input.get(c.field());
                try {
                    if (!ConditionMatcher.matches(c, v, table.tableId(), row.rowId(), col)) {
                        failed = col;
                        break;
                    }
                } catch (EvaluationException e) {
                    failed = col;
                    break;
                }
            }
            trace.add(new TraceEntry(row.rowId(), false, failed,
                    failed < 0 ? "不匹配" : "条件列#" + failed + " 不满足"));
        }
        return List.copyOf(trace);
    }

    private boolean sameActions(List<RuleRow> rows) {
        TreeMap<String, Object> first = new TreeMap<>(rows.get(0).actions());
        for (RuleRow r : rows) {
            if (!first.equals(new TreeMap<>(r.actions()))) {
                return false;
            }
        }
        return true;
    }

    private List<String> ids(List<RuleRow> rows) {
        return rows.stream().map(RuleRow::rowId).toList();
    }

    private List<String> actionFields(DecisionTable table) {
        return table.actionColumns().stream()
                .map(a -> a.field()).sorted().toList();
    }

    private EvaluationResult build(EvalStatus status, String tableId, long version, boolean gray,
                                   Map<String, Object> outputs, List<String> matchedIds,
                                   List<TraceEntry> trace, List<InputError> errors,
                                   String reason, long start, int total, int evaluated) {
        return new EvaluationResult(status, tableId, version, gray,
                new TreeMap<>(outputs), matchedIds, trace, errors, reason,
                System.nanoTime() - start, total, evaluated);
    }
}
