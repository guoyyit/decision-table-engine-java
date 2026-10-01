package com.github.guoyyit.decisiontableenginejava.engine.config;

import com.github.guoyyit.decisiontableenginejava.engine.model.ActionColumn;
import com.github.guoyyit.decisiontableenginejava.engine.model.ColumnType;
import com.github.guoyyit.decisiontableenginejava.engine.model.ConditionColumn;
import com.github.guoyyit.decisiontableenginejava.engine.model.DecisionTable;
import com.github.guoyyit.decisiontableenginejava.engine.model.HitStrategy;
import com.github.guoyyit.decisiontableenginejava.engine.model.InvalidInputPolicy;
import com.github.guoyyit.decisiontableenginejava.engine.model.Operator;
import com.github.guoyyit.decisiontableenginejava.engine.model.PublishRequest;
import com.github.guoyyit.decisiontableenginejava.engine.model.RuleRow;
import com.github.guoyyit.decisiontableenginejava.engine.store.RuleSetService;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

/**
 * 启动时发布一个示例规则集版本，便于开箱验证。
 */
@Component
public class SampleDataLoader implements ApplicationRunner {

    public static final String SAMPLE_RULE_SET = "discount-rules";

    private final RuleSetService ruleSetService;

    public SampleDataLoader(RuleSetService ruleSetService) {
        this.ruleSetService = ruleSetService;
    }

    @Override
    public void run(ApplicationArguments args) {
        ConditionColumn customerType = ConditionColumn.of(
                "customerType", "customerType", ColumnType.STRING, Operator.EQ);
        ConditionColumn orderAmount = ConditionColumn.of(
                "orderAmount", "orderAmount", ColumnType.NUMBER, Operator.GE)
                .withRange(0, 1_000_000_000);
        ConditionColumn memberLevel = ConditionColumn.of(
                "memberLevel", "memberLevel", ColumnType.ENUM, Operator.EQ, false)
                .withAllowedValues(List.of("BRONZE", "SILVER", "GOLD", "PLATINUM"));

        ActionColumn discountRate = ActionColumn.of("discountRate", ColumnType.NUMBER, 0.0);
        ActionColumn gift = ActionColumn.of("gift", ColumnType.STRING, "none");

        RuleRow r1 = RuleRow.of("vip-high", 10,
                Map.of("customerType", "VIP", "orderAmount", 1000),
                Map.of("discountRate", 0.20, "gift", "premium"));
        RuleRow r2 = RuleRow.of("vip-mid", 9,
                Map.of("customerType", "VIP", "orderAmount", 500),
                Map.of("discountRate", 0.10));
        RuleRow r3 = RuleRow.of("normal-high", 5,
                Map.of("customerType", "NORMAL", "orderAmount", 1000),
                Map.of("discountRate", 0.05));
        RuleRow r4 = RuleRow.of("normal-mid", 4,
                Map.of("customerType", "NORMAL", "orderAmount", 500),
                Map.of("discountRate", 0.02));
        RuleRow r5 = RuleRow.of("catch-all", 1,
                Map.of(),
                Map.of("discountRate", 0.0));

        DecisionTable table = DecisionTable.of(
                "order-discount",
                List.of(customerType, orderAmount, memberLevel),
                List.of(discountRate, gift),
                List.of(r1, r2, r3, r4, r5),
                HitStrategy.FIRST_MATCH,
                InvalidInputPolicy.REJECT,
                Map.of());

        ruleSetService.publish(
                new PublishRequest(SAMPLE_RULE_SET, List.of(table),
                        "初始示例版本", 100, null),
                "system");
    }
}
