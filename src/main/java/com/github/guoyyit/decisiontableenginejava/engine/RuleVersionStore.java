package com.github.guoyyit.decisiontableenginejava.engine;

import com.github.guoyyit.decisiontableenginejava.model.RuleSetVersion;

import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

/**
 * 版本仓库（内存实现）。所有已提交版本与当前生效快照均不可变；
 * 通过 CAS 替换快照引用，保证并发评估永远读不到半更新状态。
 */
@org.springframework.stereotype.Component
public class RuleVersionStore {

    private final AtomicReference<EngineSnapshot> snapshot = new AtomicReference<>(EngineSnapshot.empty());

    public EngineSnapshot snapshot() {
        return snapshot.get();
    }

    public boolean cas(EngineSnapshot expected, EngineSnapshot next) {
        return snapshot.compareAndSet(expected, next);
    }

    /** 仅供测试/初始化使用的强制替换。 */
    public void set(EngineSnapshot next) {
        snapshot.set(next);
    }

    /** 清空全部版本与审计（仅测试环境使用）。 */
    public void reset() {
        snapshot.set(EngineSnapshot.empty());
    }

    public RuleSetVersion active() {
        EngineSnapshot s = snapshot.get();
        return s.versions().get(s.activeVersion());
    }

    public List<RuleSetVersion> allVersions() {
        return List.copyOf(snapshot.get().versions().values());
    }
}
