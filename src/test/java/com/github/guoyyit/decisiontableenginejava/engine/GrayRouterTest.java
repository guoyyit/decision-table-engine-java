package com.github.guoyyit.decisiontableenginejava.engine;

import com.github.guoyyit.decisiontableenginejava.model.GrayConfig;
import com.github.guoyyit.decisiontableenginejava.model.RuleSetVersion;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

class GrayRouterTest {

    private final GrayRouter router = new GrayRouter();

    private EngineSnapshot snap(int percent, Set<String> allow, List<String> keyFields) {
        RuleSetVersion v1 = new RuleSetVersion(1, List.of(), "v1", "a", 1);
        RuleSetVersion v2 = new RuleSetVersion(2, List.of(), "v2", "a", 2);
        GrayConfig cfg = new GrayConfig(2, percent, allow, keyFields);
        return new EngineSnapshot(Map.of(1L, v1, 2L, v2), 1, cfg, List.of());
    }

    @Test
    void sameInputAlwaysRoutesSameVersionAcrossMapOrdersAndRuns() {
        EngineSnapshot s = snap(50, Set.of(), List.of());
        Map<String, Object> hashMap = new HashMap<>();
        hashMap.put("region", "CN");
        hashMap.put("age", 30);
        Map<String, Object> treeMap = new java.util.TreeMap<>();
        treeMap.put("age", 30);
        treeMap.put("region", "CN");

        GrayRouter.Route r1 = router.route(s, hashMap, null, false);
        for (int i = 0; i < 50; i++) {
            assertEquals(r1, router.route(s, i % 2 == 0 ? hashMap : treeMap, null, false));
        }
        assertEquals(1L, r1.version() == 1L || r1.version() == 2L ? r1.version() : -1L);
    }

    @Test
    void allowListForcesGrayStableKey() {
        EngineSnapshot s = snap(0, Set.of("user-42"), List.of());
        GrayRouter.Route r = router.route(s, Map.of("x", 1), "user-42", false);
        assertEquals(2L, r.version());
        assertTrue(r.gray());
    }

    @Test
    void forceGrayBypassesPercent() {
        EngineSnapshot s = snap(0, Set.of(), List.of());
        GrayRouter.Route r = router.route(s, Map.of("x", 1), null, true);
        assertEquals(2L, r.version());
    }

    @Test
    void zeroPercentOrMissingConfigGoesActive() {
        EngineSnapshot s = snap(0, Set.of(), List.of());
        for (int i = 0; i < 100; i++) {
            assertEquals(1L, router.route(s, Map.of("x", i), null, false).version());
        }
        RuleSetVersion v1 = new RuleSetVersion(1, List.of(), "v1", "a", 1);
        EngineSnapshot noGray = new EngineSnapshot(Map.of(1L, v1), 1, null, List.of());
        assertEquals(1L, router.route(noGray, Map.of("x", 1), null, true).version());
    }

    @Test
    void hundredPercentRoutesEverythingGray() {
        EngineSnapshot s = snap(100, Set.of(), List.of());
        for (int i = 0; i < 100; i++) {
            assertTrue(router.route(s, Map.of("x", "k" + i), null, false).gray());
        }
    }

    @Test
    void configuredKeyFieldsMakeRoutingIndependentOfExtraFields() {
        EngineSnapshot s = snap(50, Set.of(), List.of("tenant"));
        GrayRouter.Route r1 = router.route(s, Map.of("tenant", "t1", "noise", "a"), null, false);
        GrayRouter.Route r2 = router.route(s, Map.of("tenant", "t1", "noise", "b"), null, false);
        assertEquals(r1, r2);
    }
}
