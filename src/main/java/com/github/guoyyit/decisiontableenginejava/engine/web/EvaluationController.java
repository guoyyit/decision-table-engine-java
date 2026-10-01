package com.github.guoyyit.decisiontableenginejava.engine.web;

import com.github.guoyyit.decisiontableenginejava.engine.model.EvaluationResult;
import com.github.guoyyit.decisiontableenginejava.engine.store.RuleSetService;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * 规则评估接口。
 */
@RestController
@RequestMapping("/api/evaluate")
public class EvaluationController {

    private final RuleSetService ruleSetService;

    public EvaluationController(RuleSetService ruleSetService) {
        this.ruleSetService = ruleSetService;
    }

    /**
     * 对指定规则集的当前可用版本执行一次评估。
     *
     * @param name       规则集名
     * @param input      输入字段
     * @param routingKey 灰度分流 key（可选）
     */
    @PostMapping("/{name}")
    public EvaluationResult evaluate(@PathVariable String name,
                                    @RequestBody Map<String, Object> input,
                                    @RequestParam(required = false) String routingKey) {
        return ruleSetService.evaluate(name, input, routingKey);
    }
}
