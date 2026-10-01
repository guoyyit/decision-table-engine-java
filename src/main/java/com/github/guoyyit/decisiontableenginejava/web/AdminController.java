package com.github.guoyyit.decisiontableenginejava.web;

import com.github.guoyyit.decisiontableenginejava.engine.EngineSnapshot;
import com.github.guoyyit.decisiontableenginejava.model.AuditEvent;
import com.github.guoyyit.decisiontableenginejava.model.RuleSetVersion;
import com.github.guoyyit.decisiontableenginejava.service.DecisionService;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

/**
 * 规则管理接口。所有变更接口需要请求头：
 *   X-User: 操作人
 *   X-Roles: 角色列表，逗号分隔，需含 ROLE_RULE_ADMIN
 */
@RestController
@RequestMapping("/api/admin")
public class AdminController {

    private final DecisionService decisionService;

    public AdminController(DecisionService decisionService) {
        this.decisionService = decisionService;
    }

    @PostMapping("/versions")
    public RuleSetVersion commit(@RequestHeader("X-User") String actor,
                                 @RequestHeader(value = "X-Roles", required = false) String roles,
                                 @RequestBody CommitRequest request) {
        return decisionService.commit(actor, roles, request.message(), request.tables());
    }

    @PostMapping("/versions/{version}/activate")
    public Map<String, Object> activate(@RequestHeader("X-User") String actor,
                                        @RequestHeader(value = "X-Roles", required = false) String roles,
                                        @PathVariable long version) {
        decisionService.activate(actor, roles, version);
        return Map.of("activeVersion", version);
    }

    @PostMapping("/versions/{version}/rollback")
    public Map<String, Object> rollback(@RequestHeader("X-User") String actor,
                                        @RequestHeader(value = "X-Roles", required = false) String roles,
                                        @PathVariable long version) {
        decisionService.rollback(actor, roles, version);
        return Map.of("activeVersion", version, "rolledBack", true);
    }

    @PostMapping("/gray")
    public Map<String, Object> configureGray(@RequestHeader("X-User") String actor,
                                             @RequestHeader(value = "X-Roles", required = false) String roles,
                                             @RequestBody GrayRequest request) {
        decisionService.configureGray(actor, roles,
                request.grayVersion(), request.percent(),
                request.allowList(), request.stableKeyFields());
        return Map.of("accepted", true);
    }

    @GetMapping("/versions")
    public Map<String, Object> versions() {
        EngineSnapshot s = decisionService.currentSnapshot();
        return Map.of(
                "activeVersion", s.activeVersion(),
                "grayConfig", s.grayConfig() == null ? Map.of() : s.grayConfig(),
                "versions", s.versions().values().stream()
                        .map(v -> Map.of("version", v.version(),
                                "author", v.author(),
                                "message", String.valueOf(v.message()),
                                "committedAt", v.committedAt(),
                                "tables", v.tables().size()))
                        .toList());
    }

    @GetMapping("/audit")
    public List<AuditEvent> audit() {
        return decisionService.currentSnapshot().auditLog();
    }
}
