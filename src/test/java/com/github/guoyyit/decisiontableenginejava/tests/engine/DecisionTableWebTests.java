package com.github.guoyyit.decisiontableenginejava.tests.engine;

import com.github.guoyyit.decisiontableenginejava.engine.model.ActionColumn;
import com.github.guoyyit.decisiontableenginejava.engine.model.ColumnType;
import com.github.guoyyit.decisiontableenginejava.engine.model.ConditionColumn;
import com.github.guoyyit.decisiontableenginejava.engine.model.DecisionTable;
import com.github.guoyyit.decisiontableenginejava.engine.model.HitStrategy;
import com.github.guoyyit.decisiontableenginejava.engine.model.InvalidInputPolicy;
import com.github.guoyyit.decisiontableenginejava.engine.model.Operator;
import com.github.guoyyit.decisiontableenginejava.engine.model.PublishRequest;
import com.github.guoyyit.decisiontableenginejava.engine.model.RuleRow;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * HTTP 接口层测试：权限拒绝、非法发布、评估与审计查询。
 */
@SpringBootTest
@AutoConfigureMockMvc
class DecisionTableWebTests {

    @Autowired
    MockMvc mockMvc;
    @Autowired
    ObjectMapper objectMapper;

    private String uniqueName(String prefix) {
        return prefix + "-" + UUID.randomUUID();
    }

    private PublishRequest validPublishRequest(String name) {
        ConditionColumn level = ConditionColumn.of("level", "level", ColumnType.STRING, Operator.EQ);
        ActionColumn result = ActionColumn.of("result", ColumnType.STRING);
        DecisionTable table = DecisionTable.of("t1",
                List.of(level), List.of(result),
                List.of(RuleRow.of("r1", 10,
                        Map.of("level", "A"), Map.of("result", "ok"))),
                HitStrategy.FIRST_MATCH, InvalidInputPolicy.REJECT, Map.of());
        return new PublishRequest(name, List.of(table), "web test", 100, null);
    }

    @Test
    void publish_withoutPermission_returns403() throws Exception {
        String name = uniqueName("web-perm");
        mockMvc.perform(post("/api/rulesets/publish")
                        .header("X-User", "bob")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(validPublishRequest(name))))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.status").value(403));
    }

    @Test
    void publish_invalidRuleSet_returns400AndKeepsOldVersion() throws Exception {
        String name = uniqueName("web-invalid");
        // 先以 admin 发布一个有效版本
        mockMvc.perform(post("/api/rulesets/publish")
                        .header("X-User", "admin")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(validPublishRequest(name))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.version").value(1));

        // 再发布非法版本（引用不存在的条件列）
        ConditionColumn level = ConditionColumn.of("level", "level", ColumnType.STRING, Operator.EQ);
        ActionColumn result = ActionColumn.of("result", ColumnType.STRING);
        DecisionTable bad = DecisionTable.of("t1",
                List.of(level), List.of(result),
                List.of(RuleRow.of("bad", 10,
                        Map.of("ghost", "X"), Map.of("result", "x"))),
                HitStrategy.FIRST_MATCH, InvalidInputPolicy.REJECT, Map.of());
        mockMvc.perform(post("/api/rulesets/publish")
                        .header("X-User", "admin")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(
                                new PublishRequest(name, List.of(bad), "bad", 100, null))))
                .andExpect(status().isBadRequest());

        // 旧版本仍可评估
        mockMvc.perform(post("/api/evaluate/" + name)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of("level", "A"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.version").value(1))
                .andExpect(jsonPath("$.status").value("HIT"));
    }

    @Test
    void evaluate_returnsResultWithVersionAndActions() throws Exception {
        String name = uniqueName("web-eval");
        mockMvc.perform(post("/api/rulesets/publish")
                        .header("X-User", "publisher")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(validPublishRequest(name))))
                .andExpect(status().isOk());

        mockMvc.perform(post("/api/evaluate/" + name)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of("level", "A"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.ruleSetName").value(name))
                .andExpect(jsonPath("$.version").value(1))
                .andExpect(jsonPath("$.status").value("HIT"))
                .andExpect(jsonPath("$.actionsByTable.t1.result").value("ok"));

        // 缺失必填字段 → REJECT
        mockMvc.perform(post("/api/evaluate/" + name)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("REJECT"))
                .andExpect(jsonPath("$.errors[0].code").value("MISSING_FIELD"))
                .andExpect(jsonPath("$.errors[0].columnName").value("level"));
    }

    @Test
    void rollback_withoutPermission_returns403() throws Exception {
        String name = uniqueName("web-rollback-perm");
        mockMvc.perform(post("/api/rulesets/" + name + "/rollback")
                        .param("version", "1")
                        .header("X-User", "bob"))
                .andExpect(status().isForbidden());
    }

    @Test
    void versionsAndAudit_areQueryable() throws Exception {
        mockMvc.perform(get("/api/audit"))
                .andExpect(status().isOk());
        mockMvc.perform(get("/api/rulesets"))
                .andExpect(status().isOk());
        mockMvc.perform(get("/api/rulesets/discount-rules/versions"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].version").value(1))
                .andExpect(jsonPath("$[0].status").value("ACTIVE"));
    }
}
