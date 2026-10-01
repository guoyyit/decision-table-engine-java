package com.github.guoyyit.decisiontableenginejava.web;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;
import java.util.Map;

import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * 端到端 HTTP 测试：序列化/反序列化、状态码、权限头、审计链路。
 * server.port=0，使用 MockMvc 不占真实端口。
 */
@SpringBootTest
@AutoConfigureMockMvc
class DecisionControllerIntegrationTest {

    @Autowired
    private MockMvc mvc;
    @Autowired
    private com.github.guoyyit.decisiontableenginejava.service.DecisionService decisionService;
    private final ObjectMapper json = new ObjectMapper();

    @org.junit.jupiter.api.BeforeEach
    void reset() {
        decisionService.resetForTest();
    }

    private String validTable(String cnOutcome) {
        return """
                [{
                  "tableId":"pricing","name":"pricing","hitPolicy":"UNIQUE","tiePolicy":"CONFLICT",
                  "inputFields":{
                    "region":{"name":"region","type":"STRING","required":true,"allowed":["CN","US"]}},
                  "actionColumns":[{"field":"outcome","type":"STRING"}],
                  "rows":[
                    {"rowId":"r1","priority":0,
                     "conditions":[{"field":"region","operator":"EQ","value":"CN",
                                    "values":[],"lower":null,"upper":null,
                                    "lowerIncl":false,"upperIncl":false,"wildcard":false}],
                     "actions":{"outcome":"%s"}},
                    {"rowId":"r2","priority":0,
                     "conditions":[{"field":"region","operator":"EQ","value":"US",
                                    "values":[],"lower":null,"upper":null,
                                    "lowerIncl":false,"upperIncl":false,"wildcard":false}],
                     "actions":{"outcome":"US_X"}}
                  ]
                }]
                """.formatted(cnOutcome);
    }

    private void commit(String version, String cnOutcome) throws Exception {
        mvc.perform(post("/api/admin/versions")
                        .header("X-User", "alice")
                        .header("X-Roles", "ROLE_RULE_ADMIN")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(Map.of(
                                "message", version, "tables",
                                json.readValue(validTable(cnOutcome), List.class)))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.version").exists());
    }

    @Test
    void commitEvaluateSwitchAndRollbackEndToEnd() throws Exception {
        commit("v1", "CN_V1");
        mvc.perform(post("/api/decision/tables/pricing/evaluate")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"region":"CN"}"""))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("HIT"))
                .andExpect(jsonPath("$.version").value(1))
                .andExpect(jsonPath("$.outputs.outcome").value("CN_V1"))
                .andExpect(jsonPath("$.evaluatedRows").isNumber());

        commit("v2", "CN_V2");
        mvc.perform(post("/api/admin/versions/2/activate")
                        .header("X-User", "bob").header("X-Roles", "ROLE_RULE_ADMIN"))
                .andExpect(status().isOk());
        mvc.perform(post("/api/decision/tables/pricing/evaluate")
                        .contentType(MediaType.APPLICATION_JSON).content("""
                                {"region":"CN"}"""))
                .andExpect(jsonPath("$.version").value(2))
                .andExpect(jsonPath("$.outputs.outcome").value("CN_V2"));

        mvc.perform(post("/api/admin/versions/1/rollback")
                        .header("X-User", "bob").header("X-Roles", "ROLE_RULE_ADMIN"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.rolledBack").value(true));
        mvc.perform(post("/api/decision/tables/pricing/evaluate")
                        .contentType(MediaType.APPLICATION_JSON).content("""
                                {"region":"CN"}"""))
                .andExpect(jsonPath("$.version").value(1))
                .andExpect(jsonPath("$.outputs.outcome").value("CN_V1"));
    }

    @Test
    void viewerRoleIsForbiddenForChangesAndAuditRecordsDenial() throws Exception {
        mvc.perform(post("/api/admin/versions")
                        .header("X-User", "mallory")
                        .header("X-Roles", "ROLE_RULE_VIEWER")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"message":"evil","tables":[]}"""))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error").value("ACCESS_DENIED"));

        mvc.perform(get("/api/admin/audit"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[?(@.actor=='mallory' && @.allowed==false && @.action=='COMMIT')]")
                        .isNotEmpty());
    }

    @Test
    void invalidRulePayloadReturns422WithProblemsAndOldVersionKept() throws Exception {
        commit("v1", "CN_V1");
        String invalidTable = """
                [{
                  "tableId":"bad","name":"bad","hitPolicy":"FIRST","tiePolicy":"CONFLICT",
                  "inputFields":{"region":{"name":"region","type":"STRING","required":true,"allowed":["CN"]}},
                  "actionColumns":[{"field":"outcome","type":"STRING"}],
                  "rows":[
                    {"rowId":"x","priority":0,
                     "conditions":[{"field":"region","operator":"EQ","value":"CN","values":[],
                                    "lower":null,"upper":null,"lowerIncl":false,
                                    "upperIncl":false,"wildcard":false}],
                     "actions":{"outcome":"A"}},
                    {"rowId":"x","priority":0,
                     "conditions":[{"field":"region","operator":"EQ","value":"CN","values":[],
                                    "lower":null,"upper":null,"lowerIncl":false,
                                    "upperIncl":false,"wildcard":false}],
                     "actions":{"outcome":"B"}}
                  ]
                }]
                """;
        mvc.perform(post("/api/admin/versions")
                        .header("X-User", "alice").header("X-Roles", "ROLE_RULE_ADMIN")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(Map.of("message", "bad",
                                "tables", json.readValue(invalidTable, List.class)))))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.error").value("RULE_VALIDATION_FAILED"))
                .andExpect(jsonPath("$.details[0]", containsString("行 ID")));

        // 旧版本继续服务
        mvc.perform(post("/api/decision/tables/pricing/evaluate")
                        .contentType(MediaType.APPLICATION_JSON).content("""
                                {"region":"CN"}"""))
                .andExpect(jsonPath("$.outputs.outcome").value("CN_V1"));
    }

    @Test
    void invalidEvaluationInputIsRejectedWithExplainableErrors() throws Exception {
        commit("v1", "CN_V1");
        mvc.perform(post("/api/decision/tables/pricing/evaluate")
                        .contentType(MediaType.APPLICATION_JSON).content("""
                                {"region":"EU"}"""))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("REJECTED"))
                .andExpect(jsonPath("$.errors[0].field").value("region"))
                .andExpect(jsonPath("$.errors[0].reason").value("ENUM_VIOLATION"));

        mvc.perform(post("/api/decision/tables/pricing/evaluate")
                        .contentType(MediaType.APPLICATION_JSON).content("""
                                {}"""))
                .andExpect(jsonPath("$.status").value("REJECTED"))
                .andExpect(jsonPath("$.errors[0].reason").value("MISSING"));
    }

    @Test
    void grayReleaseRoutesStableKeys() throws Exception {
        commit("v1", "CN_V1");
        commit("v2", "CN_V2");
        mvc.perform(post("/api/admin/gray")
                        .header("X-User", "alice").header("X-Roles", "ROLE_RULE_ADMIN")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"grayVersion":2,"percent":100,"allowList":[],"stableKeyFields":[]}"""))
                .andExpect(status().isOk());
        mvc.perform(post("/api/decision/tables/pricing/evaluate")
                        .contentType(MediaType.APPLICATION_JSON).content("""
                                {"region":"CN"}"""))
                .andExpect(jsonPath("$.gray").value(true))
                .andExpect(jsonPath("$.version").value(2));

        mvc.perform(post("/api/admin/gray")
                        .header("X-User", "alice").header("X-Roles", "ROLE_RULE_ADMIN")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"grayVersion":null,"percent":0,"allowList":[],"stableKeyFields":[]}"""))
                .andExpect(status().isOk());
        mvc.perform(post("/api/decision/tables/pricing/evaluate")
                        .contentType(MediaType.APPLICATION_JSON).content("""
                                {"region":"CN"}"""))
                .andExpect(jsonPath("$.gray").value(false))
                .andExpect(jsonPath("$.version").value(1));
    }
}
