package com.github.guoyyit.decisiontableenginejava.engine;

import com.github.guoyyit.decisiontableenginejava.model.AuditEvent;
import com.github.guoyyit.decisiontableenginejava.model.GrayConfig;
import com.github.guoyyit.decisiontableenginejava.model.RuleSetVersion;

import java.util.List;
import java.util.Map;

/**
 * 引擎的不可变一致性快照：评估线程只读取快照，热更新通过整体替换引用生效。
 */
public record EngineSnapshot(Map<Long, RuleSetVersion> versions,
                             long activeVersion,
                             GrayConfig grayConfig,
                             List<AuditEvent> auditLog) {
    public EngineSnapshot {
        versions = versions == null ? Map.of() : Map.copyOf(versions);
        auditLog = auditLog == null ? List.of() : List.copyOf(auditLog);
    }

    public static EngineSnapshot empty() {
        return new EngineSnapshot(Map.of(), -1, null, List.of());
    }
}
