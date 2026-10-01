package com.github.guoyyit.decisiontableenginejava.web;

import java.util.List;
import java.util.Set;

/** 灰度配置请求。grayVersion 为 null 或 percent=0 表示关闭灰度。 */
public record GrayRequest(Long grayVersion, Integer percent,
                          Set<String> allowList, List<String> stableKeyFields) {
}
