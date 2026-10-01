package com.github.guoyyit.decisiontableenginejava.eval;

import com.github.guoyyit.decisiontableenginejava.model.FieldSchema;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static com.github.guoyyit.decisiontableenginejava.support.TestTables.*;
import static org.junit.jupiter.api.Assertions.*;

class InputValidatorTest {

    private final InputValidator validator = new InputValidator();

    @Test
    void validInputHasNoErrors() {
        Map<String, FieldSchema> schema = fields(
                str("region", true, "CN", "US"),
                num("age", true, 0.0, 150.0));
        List<InputError> errors = validator.validate("t", schema,
                Map.of("region", "CN", "age", 30));
        assertTrue(errors.isEmpty());
    }

    @Test
    void missingRequiredFieldRejected() {
        Map<String, FieldSchema> schema = fields(str("region", true, "CN"));
        List<InputError> errors = validator.validate("t", schema, Map.of());
        assertEquals(1, errors.size());
        assertEquals("MISSING", errors.get(0).reason());
        assertEquals("region", errors.get(0).field());
    }

    @Test
    void typeMismatchRangeAndEnumViolationsReported() {
        Map<String, FieldSchema> schema = fields(
                str("region", true, "CN"),
                num("age", true, 0.0, 150.0));
        List<InputError> errors = validator.validate("t", schema,
                Map.of("region", "EU", "age", 999));
        assertEquals(2, errors.size());
        // 错误顺序按字段名排序：age, region（与输入顺序无关）
        assertEquals("age", errors.get(0).field());
        assertEquals("OUT_OF_RANGE", errors.get(0).reason());
        assertEquals("region", errors.get(1).field());
        assertEquals("ENUM_VIOLATION", errors.get(1).reason());
    }

    @Test
    void unknownFieldReported() {
        Map<String, FieldSchema> schema = fields(str("region", false, "CN"));
        List<InputError> errors = validator.validate("t", schema,
                Map.of("region", "CN", "hacker", 1));
        assertTrue(errors.stream().anyMatch(e -> e.reason().equals("UNKNOWN_FIELD")
                && e.field().equals("hacker")));
    }

    @Test
    void typeMismatchIsReportedNotSilentlyPassed() {
        Map<String, FieldSchema> schema = fields(bool("flag", true));
        List<InputError> errors = validator.validate("t", schema, Map.of("flag", "true"));
        assertEquals("TYPE_MISMATCH", errors.get(0).reason());
    }
}
