package com.github.guoyyit.decisiontableenginejava.engine.eval;

import com.github.guoyyit.decisiontableenginejava.engine.model.ActionColumn;
import com.github.guoyyit.decisiontableenginejava.engine.model.ColumnType;
import com.github.guoyyit.decisiontableenginejava.engine.model.ConditionColumn;
import com.github.guoyyit.decisiontableenginejava.engine.model.ConflictDetail;
import com.github.guoyyit.decisiontableenginejava.engine.model.DecisionTable;
import com.github.guoyyit.decisiontableenginejava.engine.model.EvaluationResult;
import com.github.guoyyit.decisiontableenginejava.engine.model.EvaluationStatus;
import com.github.guoyyit.decisiontableenginejava.engine.model.HitStrategy;
import com.github.guoyyit.decisiontableenginejava.engine.model.InvalidInputPolicy;
import com.github.guoyyit.decisiontableenginejava.engine.model.MatchedRow;
import com.github.guoyyit.decisiontableenginejava.engine.model.Operator;
import com.github.guoyyit.decisiontableenginejava.engine.model.RuleRow;
import com.github.guoyyit.decisiontableenginejava.engine.model.RuleSet;
import com.github.guoyyit.decisiontableenginejava.engine.model.ValidationError;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * 规则评估器：对预编译规则集执行一次幂等、可复现的评估。
 * <p>评估语义：</p>
 * <ol>
 *   <li>逐表校验输入字段（缺失 / 类型不符 / 越界），错误可定位到表、列、行；</li>
 *   <li>通过等值索引选取候选行（无索引时退化为全表扫描），候选按
 *       (priority DESC, 行号 ASC) 排序；</li>
 *   <li>按命中策略裁决：FIRST_MATCH 取首个命中行，同优先级多命中记 DUPLICATE_HIT；
 *       ALL_MATCH 收集全部命中，动作不一致记 CONFLICT；</li>
 *   <li>汇总各表结果，状态优先级 REJECT &gt; CONFLICT &gt; DUPLICATE_HIT &gt; DEGRADE &gt; HIT &gt; NOT_HIT。</li>
 * </ol>
 * <p>全程仅使用 Map 做点查，结果列表均按确定顺序排序，
 * 不依赖字典遍历顺序与任何运行时随机化。</p>
 */
@Component
public class RuleSetEvaluator {

    /**
     * 评估给定输入。
     *
     * @param compiled 预编译规则集（某个已提交的完整版本）
     * @param input    输入字段
     * @return 评估结果
     */
    public EvaluationResult evaluate(CompiledRuleSet compiled, Map<String, Object> input) {
        long start = System.nanoTime();
        RuleSet ruleSet = compiled.ruleSet();
        Map<String, Object> safeInput = input == null ? Map.of() : input;

        List<MatchedRow> matchedRows = new ArrayList<>();
        Map<String, Map<String, Object>> actionsByTable = new LinkedHashMap<>();
        List<ValidationError> errors = new ArrayList<>();
        List<ConflictDetail> conflicts = new ArrayList<>();
        List<String> evaluatedTables = new ArrayList<>();

        boolean anyReject = false;
        boolean anyConflict = false;
        boolean anyDuplicate = false;
        boolean anyDegrade = false;
        boolean anyHit = false;

        for (DecisionTable table : ruleSet.tables()) {
            evaluatedTables.add(table.name());

            // 1. 输入校验
            Map<String, Object> values = new LinkedHashMap<>();
            List<ValidationError> tableErrors = new ArrayList<>();
            for (ConditionColumn col : table.conditionColumns()) {
                Object value = safeInput.get(col.field());
                if (value == null) {
                    if (col.required()) {
                        tableErrors.add(new ValidationError(table.name(), col.name(), null,
                                col.field(), "MISSING_FIELD",
                                "表 " + table.name() + " 条件列 " + col.name()
                                        + " 缺少必填字段 " + col.field()));
                    }
                    continue;
                }
                String typeError = validateValueType(table, col, value);
                if (typeError != null) {
                    tableErrors.add(new ValidationError(table.name(), col.name(), null,
                            col.field(), "TYPE_MISMATCH", typeError));
                    continue;
                }
                if (col.type() == ColumnType.NUMBER) {
                    double d = ((Number) value).doubleValue();
                    if (col.min() != null && d < col.min()) {
                        tableErrors.add(new ValidationError(table.name(), col.name(), null,
                                col.field(), "OUT_OF_RANGE",
                                "表 " + table.name() + " 条件列 " + col.name()
                                        + " 字段 " + col.field() + " 值 " + value
                                        + " 小于下限 " + col.min()));
                        continue;
                    }
                    if (col.max() != null && d > col.max()) {
                        tableErrors.add(new ValidationError(table.name(), col.name(), null,
                                col.field(), "OUT_OF_RANGE",
                                "表 " + table.name() + " 条件列 " + col.name()
                                        + " 字段 " + col.field() + " 值 " + value
                                        + " 大于上限 " + col.max()));
                        continue;
                    }
                }
                if (col.type() == ColumnType.ENUM
                        && !col.allowedValues().isEmpty()
                        && !col.allowedValues().contains(value)) {
                    tableErrors.add(new ValidationError(table.name(), col.name(), null,
                            col.field(), "ENUM_VIOLATION",
                            "表 " + table.name() + " 条件列 " + col.name()
                                    + " 字段 " + col.field() + " 值 " + value
                                    + " 不在允许集合 " + col.allowedValues() + " 中"));
                    continue;
                }
                values.put(col.name(), value);
            }

            if (!tableErrors.isEmpty()) {
                errors.addAll(tableErrors);
                if (table.invalidInputPolicy() == InvalidInputPolicy.DEGRADE) {
                    actionsByTable.put(table.name(), resolveDegradedActions(table));
                    anyDegrade = true;
                } else {
                    anyReject = true;
                }
                continue;
            }

            // 2. 通过等值索引选取候选行
            List<DecisionTable.IndexedRow> candidates =
                    selectCandidates(compiled, table, values);

            // 3. 逐行匹配
            List<DecisionTable.IndexedRow> matches = new ArrayList<>();
            for (DecisionTable.IndexedRow candidate : candidates) {
                if (rowMatches(table, candidate.row(), values)) {
                    matches.add(candidate);
                }
            }

            // 4. 按命中策略裁决
            if (matches.isEmpty()) {
                continue;
            }
            if (table.hitStrategy() == HitStrategy.FIRST_MATCH) {
                DecisionTable.IndexedRow winner = matches.get(0);
                List<DecisionTable.IndexedRow> samePriority = matches.stream()
                        .filter(m -> m.row().priority() == winner.row().priority())
                        .toList();
                if (samePriority.size() > 1) {
                    anyDuplicate = true;
                    for (DecisionTable.IndexedRow m : samePriority) {
                        matchedRows.add(new MatchedRow(table.name(), m.row().id(), m.row().priority()));
                    }
                } else {
                    anyHit = true;
                    matchedRows.add(new MatchedRow(table.name(), winner.row().id(), winner.row().priority()));
                }
                actionsByTable.put(table.name(), resolveRowActions(table, winner.row()));
            } else {
                // ALL_MATCH
                List<ConflictDetail> tableConflicts = detectConflicts(table, matches);
                if (!tableConflicts.isEmpty()) {
                    conflicts.addAll(tableConflicts);
                    anyConflict = true;
                    continue;
                }
                anyHit = true;
                for (DecisionTable.IndexedRow m : matches) {
                    matchedRows.add(new MatchedRow(table.name(), m.row().id(), m.row().priority()));
                }
                Map<String, Object> merged = new LinkedHashMap<>();
                for (ActionColumn act : table.actionColumns()) {
                    Object v = null;
                    for (DecisionTable.IndexedRow m : matches) {
                        Object cell = m.row().actions().get(act.name());
                        if (cell != null) {
                            v = cell;
                            break;
                        }
                    }
                    merged.put(act.name(), v != null ? v : act.defaultValue());
                }
                actionsByTable.put(table.name(), merged);
            }
        }

        EvaluationStatus status;
        if (anyReject) {
            status = EvaluationStatus.REJECT;
        } else if (anyConflict) {
            status = EvaluationStatus.CONFLICT;
        } else if (anyDuplicate) {
            status = EvaluationStatus.DUPLICATE_HIT;
        } else if (anyDegrade) {
            status = EvaluationStatus.DEGRADE;
        } else if (anyHit) {
            status = EvaluationStatus.HIT;
        } else {
            status = EvaluationStatus.NOT_HIT;
        }

        return new EvaluationResult(
                ruleSet.name(),
                ruleSet.version(),
                status,
                List.copyOf(matchedRows),
                Map.copyOf(actionsByTable),
                List.copyOf(errors),
                List.copyOf(conflicts),
                List.copyOf(evaluatedTables),
                System.nanoTime() - start);
    }

    /** 选取候选行：等值索引命中的行 + 无索引行，按 (priority DESC, 行号 ASC) 排序。 */
    private List<DecisionTable.IndexedRow> selectCandidates(CompiledRuleSet compiled,
                                                            DecisionTable table,
                                                            Map<String, Object> values) {
        Map<String, Map<Object, List<Integer>>> columnIndex =
                compiled.tableIndexes().getOrDefault(table.name(), Map.of());
        List<Integer> best = null;
        if (!columnIndex.isEmpty()) {
            for (Map.Entry<String, Map<Object, List<Integer>>> entry : columnIndex.entrySet()) {
                Object value = values.get(entry.getKey());
                if (value == null) {
                    continue;
                }
                List<Integer> candidates = entry.getValue().get(value);
                if (candidates == null) {
                    // 该等值条件下无候选，直接返回空（无索引行仍需扫描）
                    candidates = List.of();
                }
                if (best == null || candidates.size() < best.size()) {
                    best = candidates;
                }
            }
        }

        Set<Integer> candidateIdx = new LinkedHashSet<>();
        if (best != null) {
            candidateIdx.addAll(best);
        }
        candidateIdx.addAll(compiled.unindexedRows().getOrDefault(table.name(), List.of()));

        List<DecisionTable.IndexedRow> sorted = new ArrayList<>();
        for (int i = 0; i < table.rows().size(); i++) {
            if (candidateIdx.contains(i)) {
                sorted.add(new DecisionTable.IndexedRow(table.rows().get(i), i));
            }
        }
        sorted.sort((a, b) -> {
            int c = Integer.compare(b.row().priority(), a.row().priority());
            return c != 0 ? c : Integer.compare(a.index(), b.index());
        });
        return sorted;
    }

    /** 判断规则行在给定输入下是否全部条件成立。 */
    private boolean rowMatches(DecisionTable table, RuleRow row, Map<String, Object> values) {
        for (ConditionColumn col : table.conditionColumns()) {
            Object cell = row.conditions().get(col.name());
            if (cell == null) {
                continue; // 通配单元格
            }
            Object value = values.get(col.name());
            if (col.operator() == Operator.IS_NULL) {
                if (value != null) {
                    return false;
                }
                continue;
            }
            if (col.operator() == Operator.NOT_NULL) {
                if (value == null) {
                    return false;
                }
                continue;
            }
            if (value == null) {
                return false; // 单元格要求具体值而输入缺失
            }
            if (!matches(col.operator(), cell, value)) {
                return false;
            }
        }
        return true;
    }

    /** 条件匹配判定。 */
    @SuppressWarnings("unchecked")
    private boolean matches(Operator op, Object cell, Object value) {
        return switch (op) {
            case EQ -> Objects.equals(cell, value);
            case NE -> !Objects.equals(cell, value);
            // 语义：输入值(value) 与 单元格阈值(cell) 比较，如 GE 表示 value >= cell
            case GT -> compare(value, cell) > 0;
            case GE -> compare(value, cell) >= 0;
            case LT -> compare(value, cell) < 0;
            case LE -> compare(value, cell) <= 0;
            case IN -> cell instanceof Iterable<?> it && contains(it, value);
            case NOT_IN -> !(cell instanceof Iterable<?> it && contains(it, value));
            case RANGE -> {
                if (!(cell instanceof List<?> list) || list.size() != 2) {
                    yield false;
                }
                yield compare(list.get(0), value) <= 0 && compare(list.get(1), value) >= 0;
            }
            case CONTAINS -> {
                // 语义：输入值(value) 包含 单元格配置的子串/元素(cell)
                if (cell instanceof String s && value instanceof String v) {
                    yield v.contains(s);
                }
                if (value instanceof Iterable<?> it) {
                    yield contains(it, cell);
                }
                yield false;
            }
            case IS_NULL, NOT_NULL -> throw new IllegalStateException("IS_NULL/NOT_NULL 应在分支外处理");
        };
    }

    private boolean contains(Iterable<?> it, Object value) {
        for (Object item : it) {
            if (Objects.equals(item, value)) {
                return true;
            }
        }
        return false;
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    private int compare(Object a, Object b) {
        if (a instanceof Number na && b instanceof Number nb) {
            return Double.compare(na.doubleValue(), nb.doubleValue());
        }
        if (a instanceof Comparable ca && b instanceof Comparable) {
            return ca.compareTo(b);
        }
        throw new IllegalArgumentException("不可比较的类型: "
                + a.getClass().getName() + " vs " + b.getClass().getName());
    }

    /** ALL_MATCH 下检测动作冲突：同一动作列在命中行间出现不同值。 */
    private List<ConflictDetail> detectConflicts(DecisionTable table,
                                                  List<DecisionTable.IndexedRow> matches) {
        List<ConflictDetail> conflicts = new ArrayList<>();
        for (ActionColumn act : table.actionColumns()) {
            List<String> rowIds = new ArrayList<>();
            List<Object> values = new ArrayList<>();
            for (DecisionTable.IndexedRow m : matches) {
                Object cell = m.row().actions().get(act.name());
                if (cell != null) {
                    rowIds.add(m.row().id());
                    values.add(cell);
                }
            }
            long distinct = values.stream().distinct().count();
            if (distinct > 1) {
                conflicts.add(new ConflictDetail(table.name(), act.name(),
                        List.copyOf(rowIds), List.copyOf(values)));
            }
        }
        return conflicts;
    }

    /** 解析命中行动作：单元格值优先，缺省回退到动作列默认值。 */
    private Map<String, Object> resolveRowActions(DecisionTable table, RuleRow row) {
        Map<String, Object> actions = new LinkedHashMap<>();
        for (ActionColumn act : table.actionColumns()) {
            Object cell = row.actions().get(act.name());
            actions.put(act.name(), cell != null ? cell : act.defaultValue());
        }
        return actions;
    }

    /** 解析降级动作：表配置优先，缺省回退到动作列默认值。 */
    private Map<String, Object> resolveDegradedActions(DecisionTable table) {
        Map<String, Object> actions = new LinkedHashMap<>();
        for (ActionColumn act : table.actionColumns()) {
            Object v = table.degradedActions().get(act.name());
            actions.put(act.name(), v != null ? v : act.defaultValue());
        }
        return actions;
    }

    /** 校验输入值类型与列类型是否匹配，返回错误信息（null 表示通过）。 */
    private String validateValueType(DecisionTable table, ConditionColumn col, Object value) {
        ColumnType type = col.type();
        boolean ok = switch (type) {
            case STRING -> value instanceof String;
            case NUMBER -> value instanceof Number;
            case BOOLEAN -> value instanceof Boolean;
            case ENUM -> value instanceof String;
        };
        if (!ok) {
            return "表 " + table.name() + " 条件列 " + col.name() + " 字段 " + col.field()
                    + " 类型应为 " + type + "，实际为 " + value.getClass().getSimpleName();
        }
        return null;
    }
}
