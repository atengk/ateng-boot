package io.github.atengk.boot.sample.liteflow;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.atengk.boot.core.model.Result;
import io.github.atengk.boot.sample.liteflow.model.DynamicChainReloadRequest;
import io.github.atengk.boot.sample.liteflow.model.DynamicElExecuteRequest;
import io.github.atengk.boot.sample.liteflow.model.DynamicElExecuteResponse;
import io.github.atengk.boot.sample.liteflow.model.OrderFulfillmentRequest;
import io.github.atengk.boot.sample.liteflow.model.OrderFulfillmentResponse;
import io.github.atengk.boot.sample.liteflow.model.PriceCalculationRequest;
import io.github.atengk.boot.sample.liteflow.model.PriceCalculationResponse;
import io.github.atengk.boot.sample.liteflow.model.RiskApprovalRequest;
import io.github.atengk.boot.sample.liteflow.model.RiskApprovalResponse;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * LiteFlow 场景工程全量 Controller 端到端业务与时序轨迹集成测试
 *
 * @author Ateng
 * @since 2026-11-03
 */
@SpringBootTest
@AutoConfigureMockMvc
@DisplayName("LiteFlow 样例工程 E2E 全链路集成测试")
class LiteflowSampleE2eTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Test
    @DisplayName("测试场景 1.1：普通用户无券小额算价（验证不满 99 加运费 10 元）")
    void testPriceCalculationNormalUser() throws Exception {
        PriceCalculationRequest request = new PriceCalculationRequest();
        request.setOrderId("ORD_TEST_001");
        request.setUserId("user_normal_001");
        request.setOriginalAmount(new BigDecimal("50.00"));
        request.setCouponId(null);

        MvcResult mvcResult = mockMvc.perform(post("/sample/liteflow/price/calculate")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data.orderId").value("ORD_TEST_001"))
                .andReturn();

        Result<PriceCalculationResponse> result = objectMapper.readValue(
                mvcResult.getResponse().getContentAsString(StandardCharsets.UTF_8),
                new TypeReference<>() {}
        );

        PriceCalculationResponse data = result.getData();
        assertThat(data).isNotNull();
        // 原价 50，无券折扣，无等级折扣，运费 10，应付 60
        assertThat(data.getPostage()).isEqualByComparingTo("10.00");
        assertThat(data.getFinalAmount()).isEqualByComparingTo("60.00");
        assertThat(data.getTraceSummary()).contains("validateOrderCmp");
    }

    @Test
    @DisplayName("测试场景 1.2：SVIP 用户大额带券算价（验证满 99 免运费与双重优惠抵扣）")
    void testPriceCalculationSvipUser() throws Exception {
        PriceCalculationRequest request = new PriceCalculationRequest();
        request.setOrderId("ORD_TEST_002");
        request.setUserId("user_svip_888");
        request.setOriginalAmount(new BigDecimal("200.00"));
        request.setCouponId("COUPON_50");

        MvcResult mvcResult = mockMvc.perform(post("/sample/liteflow/price/calculate")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andReturn();

        Result<PriceCalculationResponse> result = objectMapper.readValue(
                mvcResult.getResponse().getContentAsString(StandardCharsets.UTF_8),
                new TypeReference<>() {}
        );

        PriceCalculationResponse data = result.getData();
        assertThat(data).isNotNull();
        // 原价 200，COUPON_50 抵扣 50，SVIP 抵扣 30，满 99 免运费 0，最终应付 120
        assertThat(data.getCouponDiscount()).isEqualByComparingTo("50.00");
        assertThat(data.getLevelDiscount()).isEqualByComparingTo("30.00");
        assertThat(data.getPostage()).isEqualByComparingTo("0.00");
        assertThat(data.getFinalAmount()).isEqualByComparingTo("120.00");
        assertThat(data.getTraceSummary()).isNotBlank();
    }

    @Test
    @DisplayName("测试场景 2.1：信贷风控命中失信黑名单（验证 IF 组件直接阻断与 REJECT 决策）")
    void testRiskApprovalBlacklist() throws Exception {
        RiskApprovalRequest request = new RiskApprovalRequest();
        request.setApplicantId("app_black_999");
        request.setIdCard("110101199001019999");
        request.setApplyAmount(new BigDecimal("100000.00"));
        request.setCreditScore(700);

        MvcResult mvcResult = mockMvc.perform(post("/sample/liteflow/risk/evaluate")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andReturn();

        Result<RiskApprovalResponse> result = objectMapper.readValue(
                mvcResult.getResponse().getContentAsString(StandardCharsets.UTF_8),
                new TypeReference<>() {}
        );

        RiskApprovalResponse data = result.getData();
        assertThat(data).isNotNull();
        assertThat(data.isApproved()).isFalse();
        assertThat(data.getDecision()).isEqualTo("REJECT");
        assertThat(data.getRiskLevel()).isEqualTo("HIGH");
        assertThat(data.getApprovedAmount()).isEqualByComparingTo("0.00");
        assertThat(data.getTraceSummary()).contains("checkBlacklistIfCmp");
    }

    @Test
    @DisplayName("测试场景 2.2：信贷风控优良客户（验证 SWITCH 分流至 PASS 极速放款）")
    void testRiskApprovalPass() throws Exception {
        RiskApprovalRequest request = new RiskApprovalRequest();
        request.setApplicantId("app_good_001");
        request.setIdCard("110101199505051234");
        request.setApplyAmount(new BigDecimal("50000.00"));
        request.setCreditScore(780);

        MvcResult mvcResult = mockMvc.perform(post("/sample/liteflow/risk/evaluate")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andReturn();

        Result<RiskApprovalResponse> result = objectMapper.readValue(
                mvcResult.getResponse().getContentAsString(StandardCharsets.UTF_8),
                new TypeReference<>() {}
        );

        RiskApprovalResponse data = result.getData();
        assertThat(data).isNotNull();
        assertThat(data.isApproved()).isTrue();
        assertThat(data.getDecision()).isEqualTo("PASS");
        assertThat(data.getRiskLevel()).isEqualTo("LOW");
        assertThat(data.getApprovedAmount()).isEqualByComparingTo("50000.00");
    }

    @Test
    @DisplayName("测试场景 2.3：信贷风控边缘客户（验证 SWITCH 分流至 MANUAL 人工复核）")
    void testRiskApprovalManualReview() throws Exception {
        RiskApprovalRequest request = new RiskApprovalRequest();
        request.setApplicantId("app_edge_002");
        request.setIdCard("110101199203035678");
        request.setApplyAmount(new BigDecimal("80000.00"));
        request.setCreditScore(620); // 介于 550 与 680 之间，触发人工复核

        MvcResult mvcResult = mockMvc.perform(post("/sample/liteflow/risk/evaluate")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andReturn();

        Result<RiskApprovalResponse> result = objectMapper.readValue(
                mvcResult.getResponse().getContentAsString(StandardCharsets.UTF_8),
                new TypeReference<>() {}
        );

        RiskApprovalResponse data = result.getData();
        assertThat(data).isNotNull();
        assertThat(data.isApproved()).isFalse();
        assertThat(data.getDecision()).isEqualTo("MANUAL");
        assertThat(data.getRiskLevel()).isEqualTo("MEDIUM");
        assertThat(data.getApprovedAmount()).isEqualByComparingTo("40000.00");
    }

    @Test
    @DisplayName("测试场景 3.1：订单履约正向成功流（验证全节点顺利流转）")
    void testOrderFulfillmentSuccess() throws Exception {
        OrderFulfillmentRequest request = new OrderFulfillmentRequest();
        request.setOrderId("ORD_FULFILL_001");
        request.setSkuId("SKU_IPHONE_16");
        request.setQuantity(2);
        request.setSimulateFailure(false);

        MvcResult mvcResult = mockMvc.perform(post("/sample/liteflow/fulfillment/fulfill")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andReturn();

        Result<OrderFulfillmentResponse> result = objectMapper.readValue(
                mvcResult.getResponse().getContentAsString(StandardCharsets.UTF_8),
                new TypeReference<>() {}
        );

        OrderFulfillmentResponse data = result.getData();
        assertThat(data).isNotNull();
        assertThat(data.isSuccess()).isTrue();
        assertThat(data.getFulfillmentStatus()).isEqualTo("FULFILLED");
        assertThat(data.getCompensationLogs()).isEmpty();
    }

    @Test
    @DisplayName("测试场景 3.2：订单履约下游通知超时故障注入（验证 Saga 逆序补偿自动回滚库存与运单）")
    void testOrderFulfillmentCompensation() throws Exception {
        OrderFulfillmentRequest request = new OrderFulfillmentRequest();
        request.setOrderId("ORD_FULFILL_FAIL_002");
        request.setSkuId("SKU_MACBOOK_PRO");
        request.setQuantity(1);
        request.setSimulateFailure(true); // 故障注入触发补偿

        MvcResult mvcResult = mockMvc.perform(post("/sample/liteflow/fulfillment/fulfill")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andReturn();

        Result<OrderFulfillmentResponse> result = objectMapper.readValue(
                mvcResult.getResponse().getContentAsString(StandardCharsets.UTF_8),
                new TypeReference<>() {}
        );

        OrderFulfillmentResponse data = result.getData();
        assertThat(data).isNotNull();
        assertThat(data.isSuccess()).isFalse();
        assertThat(data.getFulfillmentStatus()).isEqualTo("COMPENSATED");
        // 核心验证：前序节点必须按逆序被触发 rollback，产生补偿审计日志
        assertThat(data.getCompensationLogs()).hasSize(2);
        assertThat(data.getCompensationLogs().get(0)).contains("仓储物流");
        assertThat(data.getCompensationLogs().get(1)).contains("库存服务");
    }

    @Test
    @DisplayName("测试场景 4.1：动态规则探测与热更新 (containChain & reloadChain)")
    void testDynamicChainReloadAndProbe() throws Exception {
        // 1. 探测已有预置链
        mockMvc.perform(get("/sample/liteflow/dynamic/contain-chain")
                        .param("chainId", "price-calc-flow"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data").value(true));

        // 2. 动态注册一条新链
        DynamicChainReloadRequest reloadRequest = new DynamicChainReloadRequest();
        reloadRequest.setChainId("dynamic-custom-chain");
        reloadRequest.setEl("THEN(dynamicValidateCmp, dynamicTransformCmp);");

        mockMvc.perform(post("/sample/liteflow/dynamic/reload-chain")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(reloadRequest)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data").value(true));

        // 3. 再次探测新链已被加载
        mockMvc.perform(get("/sample/liteflow/dynamic/contain-chain")
                        .param("chainId", "dynamic-custom-chain"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data").value(true));
    }

    @Test
    @DisplayName("测试场景 4.2：在线即时执行 EL 表达式 (executeElWithTrace)")
    void testDynamicElExecute() throws Exception {
        DynamicElExecuteRequest request = new DynamicElExecuteRequest();
        request.setEl("THEN(dynamicValidateCmp, dynamicEchoCmp);");
        request.setPayload("Antigravity_LiteFlow_Engine");

        MvcResult mvcResult = mockMvc.perform(post("/sample/liteflow/dynamic/execute-el")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andReturn();

        Result<DynamicElExecuteResponse> result = objectMapper.readValue(
                mvcResult.getResponse().getContentAsString(StandardCharsets.UTF_8),
                new TypeReference<>() {}
        );

        DynamicElExecuteResponse data = result.getData();
        assertThat(data).isNotNull();
        assertThat(data.isSuccess()).isTrue();
        assertThat(data.getOutputData()).containsKey("echoPayload");
        assertThat(data.getOutputData().get("echoPayload")).isEqualTo("ECHO:Antigravity_LiteFlow_Engine");
    }

    @Test
    @DisplayName("测试场景 4.3：领域事件总线热更通知发布 (refresh-event)")
    void testRefreshEventBus() throws Exception {
        // 1. 单链热更事件
        mockMvc.perform(post("/sample/liteflow/dynamic/refresh-event")
                        .param("chainId", "price-calc-flow")
                        .param("el", "THEN(validateOrderCmp, calculatePostageCmp);"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data").value(org.hamcrest.Matchers.containsString("price-calc-flow")));

        // 2. 全量规则重载事件
        mockMvc.perform(post("/sample/liteflow/dynamic/refresh-event"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data").value(org.hamcrest.Matchers.containsString("全量")));
    }
}
