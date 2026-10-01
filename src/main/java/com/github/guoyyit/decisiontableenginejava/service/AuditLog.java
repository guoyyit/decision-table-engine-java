package com.github.guoyyit.decisiontableenginejava.service;

import com.github.guoyyit.decisiontableenginejava.engine.EngineSnapshot;
import com.github.guoyyit.decisiontableenginejava.engine.RuleVersionStore;
import com.github.guoyyit.decisiontableenginejava.model.AuditEvent;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * 审计日志：随快照不可变追加；读永远基于当前快照。
 */
@Component
public class AuditLog {

    private final RuleVersionStore store;

    public AuditLog(RuleVersionStore store) {
        this.store = store;
    }

    public List<AuditEvent> events() {
        return store.snapshot().auditLog();
    }

    /** 在快照替换路径中调用：生成追加事件后的新日志。 */
    public List<AuditEvent> append(List<AuditEvent> base, AuditEvent event) {
        List<AuditEvent> next = new ArrayList<>(base);
        next.add(event);
        return List.copyOf(next);
    }
}
