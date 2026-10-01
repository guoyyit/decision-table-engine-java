package com.github.guoyyit.decisiontableenginejava.engine.audit;

import com.github.guoyyit.decisiontableenginejava.engine.model.AuditRecord;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 审计服务：记录规则变更与关键评估操作，所有记录可追溯。
 * <p>使用 CopyOnWriteArrayList 保证并发发布与查询下的线程安全。</p>
 */
@Service
public class AuditService {

    private final List<AuditRecord> records = new CopyOnWriteArrayList<>();
    private final AtomicLong idGen = new AtomicLong(0);

    /** 记录一条审计。 */
    public void record(String user, String action, String target, String result, String detail) {
        records.add(new AuditRecord(
                idGen.incrementAndGet(),
                Instant.now(),
                user == null ? "anonymous" : user,
                action,
                target,
                result,
                detail == null ? "" : detail));
    }

    /** 记录成功的操作。 */
    public void success(String user, String action, String target, String detail) {
        record(user, action, target, "SUCCESS", detail);
    }

    /** 记录被拒绝的操作（越权）。 */
    public void denied(String user, String action, String target, String detail) {
        record(user, action, target, "DENIED", detail);
    }

    /** 记录失败的操作（校验不通过、编译失败等）。 */
    public void failure(String user, String action, String target, String detail) {
        record(user, action, target, "FAILURE", detail);
    }

    /** 返回全部审计记录（按时间顺序）。 */
    public List<AuditRecord> list() {
        return List.copyOf(records);
    }
}
