package com.github.guoyyit.decisiontableenginejava.engine.web;

import com.github.guoyyit.decisiontableenginejava.engine.audit.AuditService;
import com.github.guoyyit.decisiontableenginejava.engine.model.AuditRecord;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 审计记录查询接口。
 */
@RestController
@RequestMapping("/api/audit")
public class AuditController {

    private final AuditService auditService;

    public AuditController(AuditService auditService) {
        this.auditService = auditService;
    }

    /** 返回全部审计记录（按时间顺序）。 */
    @GetMapping
    public List<AuditRecord> list() {
        return auditService.list();
    }
}
