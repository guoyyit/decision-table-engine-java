package com.github.guoyyit.decisiontableenginejava.model;

import java.util.List;
import java.util.Set;

/**
 * 灰度配置：灰度版本 + 稳定 hash 百分比 [0,100] + 白名单 key。
 *
 * @param grayVersion     灰度版本号
 * @param grayPercent     灰度流量百分比（按输入稳定哈希）
 * @param allowList       始终进入灰度的 key 集合
 * @param stableKeyFields 计算稳定哈希所使用的输入字段（为空则用全部输入）
 */
public record GrayConfig(long grayVersion, int grayPercent,
                         Set<String> allowList, List<String> stableKeyFields) {
    public GrayConfig {
        allowList = allowList == null ? Set.of() : Set.copyOf(allowList);
        stableKeyFields = stableKeyFields == null ? List.of() : List.copyOf(stableKeyFields);
    }
}
