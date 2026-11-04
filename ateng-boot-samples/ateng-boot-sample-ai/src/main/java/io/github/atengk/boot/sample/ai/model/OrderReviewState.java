package io.github.atengk.boot.sample.ai.model;

import io.github.atengk.boot.ai.graph.BaseGraphState;
import io.github.atengk.boot.ai.graph.TypedKey;

import java.math.BigDecimal;
import java.util.Collections;
import java.util.List;
import java.util.Map;

/**
 * 订单风控审核认知图全局状态模型
 *
 * @author Ateng
 * @since 2026-11-03
 */
public class OrderReviewState extends BaseGraphState {

    public static final TypedKey<String> ORDER_ID = TypedKey.of("orderId", String.class);
    public static final TypedKey<BigDecimal> AMOUNT = TypedKey.of("amount", BigDecimal.class);
    public static final TypedKey<Integer> CREDIT_SCORE = TypedKey.of("creditScore", Integer.class);
    public static final TypedKey<String> RISK_LEVEL = TypedKey.of("riskLevel", String.class);
    public static final TypedKey<Integer> DOC_RETRY_COUNT = TypedKey.of("docRetryCount", Integer.class);
    public static final TypedKey<String> ADDITIONAL_DOCS = TypedKey.of("additionalDocs", String.class);
    public static final TypedKey<String> REMARKS = TypedKey.of("remarks", String.class);
    public static final TypedKey<String> FINAL_STATUS = TypedKey.of("finalStatus", String.class);
    public static final TypedKey<String> REASON = TypedKey.of("reason", String.class);

    public OrderReviewState() {
        super(Collections.emptyMap());
    }

    public OrderReviewState(Map<String, Object> initData) {
        super(initData);
    }
}
