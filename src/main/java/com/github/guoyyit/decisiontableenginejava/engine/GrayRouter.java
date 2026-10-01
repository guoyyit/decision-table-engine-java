package com.github.guoyyit.decisiontableenginejava.engine;

import com.github.guoyyit.decisiontableenginejava.model.GrayConfig;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * 灰度路由器。
 *
 * 规则（确定性、可复现）：
 *  1. forceGray=true：直接路由灰度版本（调试/压测用，仍要求灰度版本存在）。
 *  2. stableKey 命中 allowList：路由灰度版本。
 *  3. 否则用稳定键的 SHA-256 前 8 字节取模 100，
 *     bucket < grayPercent 时路由灰度版本；其余走当前生效（active）版本。
 *
 * 稳定键构造（顺序无关）：
 *  - 配置了 stableKeyFields 时按该顺序拼接字段值；
 *  - 否则将输入按 key 字典序（TreeMap）拼接；
 *  - 不使用 Object.hashCode（JVM 加盐随机化），不使用随机数、时间戳。
 */
@Component
public class GrayRouter {

    public record Route(long version, boolean gray) {
    }

    public Route route(EngineSnapshot snapshot, Map<String, Object> input,
                       String stableKey, boolean forceGray) {
        GrayConfig cfg = snapshot.grayConfig();
        long active = snapshot.activeVersion();
        if (cfg == null || !snapshot.versions().containsKey(cfg.grayVersion())) {
            return new Route(active, false);
        }
        if (forceGray) {
            return new Route(cfg.grayVersion(), true);
        }
        if (stableKey != null && cfg.allowList().contains(stableKey)) {
            return new Route(cfg.grayVersion(), true);
        }
        String key = stableKey != null ? stableKey : buildStableKey(cfg.stableKeyFields(), input);
        int bucket = bucket(key);
        if (bucket < cfg.grayPercent()) {
            return new Route(cfg.grayVersion(), true);
        }
        return new Route(active, false);
    }

    private String buildStableKey(List<String> fields, Map<String, Object> input) {
        StringBuilder sb = new StringBuilder();
        if (fields.isEmpty()) {
            for (Map.Entry<String, Object> e : new TreeMap<>(input).entrySet()) {
                sb.append(e.getKey()).append('=').append(e.getValue()).append(';');
            }
        } else {
            for (String f : fields) {
                sb.append(f).append('=').append(input.get(f)).append(';');
            }
        }
        return sb.toString();
    }

    private int bucket(String key) {
        try {
            byte[] digest = java.security.MessageDigest.getInstance("SHA-256")
                    .digest(key.getBytes(StandardCharsets.UTF_8));
            long h = 0;
            for (int i = 0; i < 8; i++) {
                h = (h << 8) | (digest[i] & 0xffL);
            }
            long mod = Math.floorMod(h, 100L);
            return (int) mod;
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 不可用", e);
        }
    }
}
