package com.github.guoyyit.decisiontableenginejava.engine;

import com.github.guoyyit.decisiontableenginejava.eval.ConditionMatcher;
import com.github.guoyyit.decisiontableenginejava.eval.EvaluationException;
import com.github.guoyyit.decisiontableenginejava.model.ConditionColumn;
import com.github.guoyyit.decisiontableenginejava.model.DecisionTable;
import com.github.guoyyit.decisiontableenginejava.model.Operator;
import com.github.guoyyit.decisiontableenginejava.model.RuleRow;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

/**
 * 编译后的决策表（不可变）。
 *
 * 预编译索引：
 *  对每个条件列，若该列在“所有行”上都是 EQ 或 IN（无通配、无其它操作符、无越位行），
 *  则构建 归一化字面值 -> 候选行下标集合 的哈希索引。
 *  索引仅用于缩小候选范围，绝不代替判定：
 *   1. 对输入值命中的每个列索引取候选集合，选择其中最小的一列作为候选
 *      （该集合是跨列 AND 交集的超集，选择性不弱于严格交集）；
 *   2. 候选行再逐列执行完整条件判定（含非索引列），交集语义由完整判定保证；
 *   3. 任何索引列查不到字面值 => 结果必然为空，直接返回空候选。
 *  没有任何可用索引时退化为全表顺序扫描。
 *
 * 复杂度上界（详见 docs/DECISION_TABLE_ENGINE.md）：
 *  索引命中：O(c + k·c)，k 为最小候选数，c 为条件列数；
 *  最坏退化：O(n·c)，n 为规则行数；构建索引为一次性 O(n·c)。
 */
public final class CompiledTable {

    /** 单列索引：归一化字面量 key -> 候选行下标集合（有序存储）。 */
    private record ColumnIndex(String field, Map<Object, int[]> literals) {
    }

    private final DecisionTable table;
    private final List<ColumnIndex> indexes;

    private CompiledTable(DecisionTable table) {
        this.table = table;
        this.indexes = buildIndexes(table);
    }

    public static CompiledTable compile(DecisionTable table) {
        return new CompiledTable(table);
    }

    public DecisionTable table() {
        return table;
    }

    public boolean hasIndex() {
        return !indexes.isEmpty();
    }

    public Set<String> indexedColumns() {
        Set<String> cols = new TreeSet<>();
        for (ColumnIndex idx : indexes) {
            cols.add(idx.field());
        }
        return cols;
    }

    private static List<ColumnIndex> buildIndexes(DecisionTable t) {
        int rowCount = t.rows().size();
        int maxCols = t.rows().stream().mapToInt(r -> r.conditions().size()).max().orElse(0);
        List<ColumnIndex> result = new ArrayList<>();

        for (int col = 0; col < maxCols; col++) {
            Map<Object, List<Integer>> bucket = new HashMap<>();
            String fieldName = null;
            boolean indexable = true;

            for (int rowIdx = 0; rowIdx < rowCount; rowIdx++) {
                RuleRow row = t.rows().get(rowIdx);
                if (col >= row.conditions().size()) {
                    // 越位行（该列缺失 = 通配语义）：任何值都可能命中，不能安全索引
                    indexable = false;
                    break;
                }
                ConditionColumn c = row.conditions().get(col);
                if (c == null || c.wildcard()) {
                    indexable = false;
                    break;
                }
                if (fieldName == null) {
                    fieldName = c.field();
                }
                List<Object> keys = null;
                if (c.operator() == Operator.EQ && c.value() != null) {
                    keys = List.of(c.value());
                } else if (c.operator() == Operator.IN && !c.values().isEmpty()) {
                    keys = c.values();
                }
                if (keys == null) {
                    indexable = false;
                    break;
                }
                for (Object k : keys) {
                    bucket.computeIfAbsent(normalize(k), x -> new ArrayList<>()).add(rowIdx);
                }
            }

            if (indexable && fieldName != null) {
                Map<Object, int[]> sorted = new HashMap<>();
                for (Map.Entry<Object, List<Integer>> e : bucket.entrySet()) {
                    int[] arr = e.getValue().stream().mapToInt(Integer::intValue).sorted().toArray();
                    sorted.put(e.getKey(), arr);
                }
                result.add(new ColumnIndex(fieldName, Map.copyOf(sorted)));
            }
        }
        return List.copyOf(result);
    }

    /**
     * 返回索引交集筛选后的候选行；无可用索引时返回全部行。
     * 候选行下标按定义顺序排列，保证后续判定与轨迹确定性。
     */
    public List<RuleRow> candidateRows(Map<String, Object> input) {
        int[] best = null;
        for (ColumnIndex idx : indexes) {
            Object v = input.get(idx.field());
            if (v == null) {
                // 该索引字段输入缺失：无法用此列缩减（缺失行在逐列判定时处理）
                continue;
            }
            int[] cand = idx.literals().get(normalize(v));
            if (cand == null) {
                // 该列存在索引但无任何行对应该值 => 不可能命中
                return List.of();
            }
            if (best == null || cand.length < best.length) {
                best = cand;
            }
        }
        if (best == null) {
            return table.rows();
        }
        List<RuleRow> rows = new ArrayList<>(best.length);
        for (int i : best) {
            rows.add(table.rows().get(i));
        }
        return rows;
    }

    /**
     * 对候选行执行全部条件列的完整判定。索引只负责筛选，
     * 所有列（包括非索引列与同列的其它行差异）都在此重新判定。
     */
    public List<RuleRow> matchRows(List<RuleRow> candidates, Map<String, Object> input) {
        List<RuleRow> matched = new ArrayList<>();
        for (RuleRow row : candidates) {
            boolean ok = true;
            for (int col = 0; col < row.conditions().size(); col++) {
                ConditionColumn c = row.conditions().get(col);
                String field = c == null ? null : c.field();
                Object v = field == null ? null : input.get(field);
                if (!ConditionMatcher.matches(c, v, table.tableId(), row.rowId(), col)) {
                    ok = false;
                    break;
                }
            }
            if (ok) {
                matched.add(row);
            }
        }
        return matched;
    }

    private static Object normalize(Object o) {
        if (o instanceof Number n) {
            return n.doubleValue();
        }
        return o;
    }
}
