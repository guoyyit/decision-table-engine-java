package com.github.guoyyit.decisiontableenginejava.web;

import com.github.guoyyit.decisiontableenginejava.eval.EvaluationResult;
import com.github.guoyyit.decisiontableenginejava.service.DecisionService;
import com.github.guoyyit.decisiontableenginejava.service.InputPolicy;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

/**
 * 决策表引擎 HTTP 接口。
 */
@RestController
@RequestMapping("/api/decision")
public class DecisionController {

    private final DecisionService decisionService;

    public DecisionController(DecisionService decisionService) {
        this.decisionService = decisionService;
    }

    @PostMapping("/tables/{tableId}/evaluate")
    public EvaluationResult evaluate(@PathVariable String tableId,
                                     @RequestBody Map<String, Object> input,
                                     @RequestHeader(value = "X-Stable-Key", required = false) String stableKey,
                                     @RequestHeader(value = "X-Force-Gray", required = false, defaultValue = "false")
                                     boolean forceGray,
                                     @RequestParam(value = "policy", required = false, defaultValue = "REJECT")
                                     InputPolicy policy) {
        return decisionService.evaluate(tableId, input, stableKey, forceGray, policy);
    }
}
