package com.github.guoyyit.decisiontableenginejava.eval;

import com.github.guoyyit.decisiontableenginejava.model.ConditionColumn;

/**
 * 单个条件列的判定器（纯函数、无状态、无线程安全问题）。
 *
 * 语义约定（文档化的三值判定）：
 *  - 通配 wildcard=true：任意取值（含缺失 null）都满足。
 *  - PRESENT：值非 null 即满足。
 *  - 字段缺失（null）且操作符非 wildcard/PRESENT：一律不满足
 *    （NE/NOT_IN 对缺失值同样不满足——缺失不是“不等”，避免静默放行）。
 *  - EQ/NE/IN/NOT_IN 支持 STRING/NUMBER/BOOLEAN；
 *    数值统一按 double 比较，不受 Integer/Long/Double 反序列化差异影响。
 *  - GT/GTE/LT/LTE/RANGE 仅允许 NUMBER，类型不符抛 EvaluationException。
 *  - 类型不可比（STRING 与 NUMBER 相比较）抛 EvaluationException，可定位 rowId/列序号。
 */
public final class ConditionMatcher {

    private ConditionMatcher() {
    }

    public static boolean matches(ConditionColumn c, Object value,
                                  String tableId, String rowId, int columnIndex) {
        if (c == null || c.wildcard()) {
            return true;
        }
        switch (c.operator()) {
            case PRESENT -> {
                return value != null;
            }
            case EQ -> {
                return value != null && eq(c.value(), value, tableId, rowId, columnIndex);
            }
            case NE -> {
                return value != null && !eq(c.value(), value, tableId, rowId, columnIndex);
            }
            case IN -> {
                if (value == null) {
                    return false;
                }
                for (Object v : c.values()) {
                    if (eq(v, value, tableId, rowId, columnIndex)) {
                        return true;
                    }
                }
                return false;
            }
            case NOT_IN -> {
                if (value == null) {
                    return false;
                }
                for (Object v : c.values()) {
                    if (eq(v, value, tableId, rowId, columnIndex)) {
                        return false;
                    }
                }
                return true;
            }
            case GT, GTE, LT, LTE -> {
                double left = asNumber(value, tableId, rowId, columnIndex);
                double right = asNumber(c.value(), tableId, rowId, columnIndex);
                return switch (c.operator()) {
                    case GT -> left > right;
                    case GTE -> left >= right;
                    case LT -> left < right;
                    case LTE -> left <= right;
                    default -> false;
                };
            }
            case RANGE -> {
                double d = asNumber(value, tableId, rowId, columnIndex);
                if (c.lower() != null) {
                    double lo = asNumber(c.lower(), tableId, rowId, columnIndex);
                    if (c.lowerIncl() ? d < lo : d <= lo) {
                        return false;
                    }
                }
                if (c.upper() != null) {
                    double hi = asNumber(c.upper(), tableId, rowId, columnIndex);
                    if (c.upperIncl() ? d > hi : d >= hi) {
                        return false;
                    }
                }
                return true;
            }
            default -> throw new EvaluationException(tableId, rowId, columnIndex,
                    "未知操作符: " + c.operator());
        }
    }

    private static boolean eq(Object a, Object b, String tableId, String rowId, int col) {
        if (a == null || b == null) {
            return false;
        }
        boolean na = isNumber(a);
        boolean nb = isNumber(b);
        if (na || nb) {
            if (na != nb) {
                throw new EvaluationException(tableId, rowId, col,
                        "条件比较类型不匹配: 数值与非数值 (" + a + " vs " + b + ")");
            }
            return ((Number) a).doubleValue() == ((Number) b).doubleValue();
        }
        if (a instanceof Boolean || b instanceof Boolean) {
            if (a.getClass() != b.getClass()) {
                throw new EvaluationException(tableId, rowId, col,
                        "条件比较类型不匹配: 布尔与非布尔 (" + a + " vs " + b + ")");
            }
            return a.equals(b);
        }
        return a.toString().equals(b.toString());
    }

    private static boolean isNumber(Object o) {
        return o instanceof Number;
    }

    private static double asNumber(Object o, String tableId, String rowId, int col) {
        if (o instanceof Number n) {
            return n.doubleValue();
        }
        throw new EvaluationException(tableId, rowId, col,
                "期望数值类型，实际为: " + (o == null ? "null" : o.getClass().getSimpleName()));
    }
}
