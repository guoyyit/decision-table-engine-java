package com.github.guoyyit.decisiontableenginejava.eval;

import com.github.guoyyit.decisiontableenginejava.model.DataType;
import com.github.guoyyit.decisiontableenginejava.model.FieldSchema;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * 运行时输入校验器。
 *
 * 策略（确定性、可解释，绝不静默放行）：
 *  - 必填字段缺失           -> MISSING
 *  - 类型不匹配             -> TYPE_MISMATCH（STRING/NUMBER/BOOLEAN 严格匹配）
 *  - NUMBER 超出 [min,max] -> OUT_OF_RANGE
 *  - STRING 不在 allowed   -> ENUM_VIOLATION
 *  - schema 未声明的字段    -> UNKNOWN_FIELD
 * 错误按字段名排序后返回，保证错误顺序与输入 Map 遍历顺序无关。
 * 非必填字段缺失时：不做类型/范围检查（表示“未提供”），条件匹配按缺失语义处理。
 */
@Component
public class InputValidator {

    public List<InputError> validate(String tableId,
                                     Map<String, FieldSchema> schema,
                                     Map<String, Object> input) {
        List<InputError> errors = new ArrayList<>();
        TreeMap<String, Object> sortedInput = new TreeMap<>(input);

        for (Map.Entry<String, FieldSchema> e : new TreeMap<>(schema).entrySet()) {
            String name = e.getKey();
            FieldSchema f = e.getValue();
            Object v = input.get(name);
            if (v == null) {
                if (f.required()) {
                    errors.add(new InputError(tableId, name, "MISSING",
                            "必填字段缺失: " + name));
                }
                continue;
            }
            if (!typeOk(f.type(), v)) {
                errors.add(new InputError(tableId, name, "TYPE_MISMATCH",
                        "字段 " + name + " 期望类型 " + f.type()
                                + "，实际类型 " + v.getClass().getSimpleName()));
                continue;
            }
            if (f.type() == DataType.NUMBER) {
                double d = ((Number) v).doubleValue();
                if (f.min() != null && d < f.min()) {
                    errors.add(new InputError(tableId, name, "OUT_OF_RANGE",
                            "字段 " + name + " 值 " + d + " 小于最小值 " + f.min()));
                }
                if (f.max() != null && d > f.max()) {
                    errors.add(new InputError(tableId, name, "OUT_OF_RANGE",
                            "字段 " + name + " 值 " + d + " 大于最大值 " + f.max()));
                }
            }
            if (f.type() == DataType.STRING && !f.allowed().isEmpty()
                    && !f.allowed().contains(v.toString())) {
                errors.add(new InputError(tableId, name, "ENUM_VIOLATION",
                        "字段 " + name + " 值 " + v + " 不在允许集合 " + f.allowed() + " 中"));
            }
        }

        for (String name : sortedInput.keySet()) {
            if (!schema.containsKey(name)) {
                errors.add(new InputError(tableId, name, "UNKNOWN_FIELD",
                        "未知输入字段: " + name));
            }
        }
        return errors;
    }

    private boolean typeOk(DataType type, Object v) {
        return switch (type) {
            case STRING -> v instanceof String;
            case NUMBER -> v instanceof Number;
            case BOOLEAN -> v instanceof Boolean;
        };
    }
}
