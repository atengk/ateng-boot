package io.github.atengk.boot.sample.core;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.atengk.boot.core.context.TenantContextHolder;
import io.github.atengk.boot.core.context.UserContextHolder;
import io.github.atengk.boot.core.util.AsyncUtil;
import io.github.atengk.boot.sample.core.context.SampleCustomContextHolder;
import io.github.atengk.boot.sample.core.dto.AsyncBatchSupplyRequest;
import io.github.atengk.boot.sample.core.dto.AsyncPropagateRequest;
import io.github.atengk.boot.sample.core.dto.AsyncRiskCheckRequest;
import io.github.atengk.boot.sample.core.dto.PoolGovernanceRequest;
import io.github.atengk.boot.sample.core.dto.ReportExportRequest;
import io.github.atengk.boot.sample.core.dto.TxMqCooperateRequest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.notNullValue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * CoreAsyncAndContextController 异步与上下文控制器端到端 Web 集成测试
 * 覆盖多维上下文透传、线程池舱壁隔离、运行时动态热调参及事务消息传播协同 4 大业务端点，并在每项测试完成后彻底清理现场
 *
 * @author Ateng
 * @since 2026-11-07
 */
@SpringBootTest
@AutoConfigureMockMvc
class CoreAsyncAndContextControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @AfterEach
    void tearDown() {
        // 1. 无条件严格清理线程上下文，杜绝跨测试脏读
        UserContextHolder.clear();
        TenantContextHolder.clear();
        SampleCustomContextHolder.clear();
        MDC.clear();

        // 2. 严格还原受管业务线程池参数，防御热调参污染后续测试
        try {
            AsyncUtil.resize("export", 2, 4);
            AsyncUtil.resize("report", 2, 4);
        } catch (Exception ignored) {
            // 忽略未初始化异常
        }
    }

    @Test
    @DisplayName("测试四维全链路上下文与扩展快照 SPI 跨线程池完整透传")
    void testPropagateContext_Success() throws Exception {
        AsyncPropagateRequest request = AsyncPropagateRequest.builder()
                .userId("user-prod-8888")
                .tenantId("tenant-finance-001")
                .customContextData("CUSTOM-METRIC-TRACE-PAYLOAD")
                .build();

        mockMvc.perform(post("/sample/core/async/propagate")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code", is(200)))
                .andExpect(jsonPath("$.success", is(true)))
                .andExpect(jsonPath("$.data.contextMatched", is(true)))
                .andExpect(jsonPath("$.data.mainUserId", is("user-prod-8888")))
                .andExpect(jsonPath("$.data.asyncUserId", is("user-prod-8888")))
                .andExpect(jsonPath("$.data.mainTenantId", is("tenant-finance-001")))
                .andExpect(jsonPath("$.data.asyncTenantId", is("tenant-finance-001")))
                .andExpect(jsonPath("$.data.customSnapshotValue", is("CUSTOM-METRIC-TRACE-PAYLOAD")))
                .andExpect(jsonPath("$.data.mainTraceId", notNullValue()))
                .andExpect(jsonPath("$.data.asyncTraceId", notNullValue()))
                .andExpect(jsonPath("$.data.asyncThreadName", containsString("sample-export-async-")));
    }

    @Test
    @DisplayName("测试多业务受管线程池舱壁隔离与异步协同")
    void testExportReportBulkhead_Success() throws Exception {
        ReportExportRequest request = ReportExportRequest.builder()
                .reportName("Q4-Financial-Analysis.xlsx")
                .format("EXCEL")
                .build();

        mockMvc.perform(post("/sample/core/async/export-report")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code", is(200)))
                .andExpect(jsonPath("$.success", is(true)))
                .andExpect(jsonPath("$.data.reportName", is("Q4-Financial-Analysis.xlsx")))
                .andExpect(jsonPath("$.data.bulkheadIsolated", is(true)))
                .andExpect(jsonPath("$.data.exportThreadName", containsString("sample-export-async-")))
                .andExpect(jsonPath("$.data.reportThreadName", containsString("sample-report-async-")));
    }

    @Test
    @DisplayName("测试受管线程池全景状态监控指标探测与动态热调参")
    void testGovernThreadPool_Success() throws Exception {
        PoolGovernanceRequest request = PoolGovernanceRequest.builder()
                .poolName("export")
                .newCorePoolSize(6)
                .newMaxPoolSize(12)
                .build();

        mockMvc.perform(post("/sample/core/async/pool-governance")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code", is(200)))
                .andExpect(jsonPath("$.success", is(true)))
                .andExpect(jsonPath("$.data.poolName", is("export")))
                .andExpect(jsonPath("$.data.beforeCorePoolSize", is(2)))
                .andExpect(jsonPath("$.data.beforeMaxPoolSize", is(4)))
                .andExpect(jsonPath("$.data.afterCorePoolSize", is(6)))
                .andExpect(jsonPath("$.data.afterMaxPoolSize", is(12)))
                .andExpect(jsonPath("$.data.virtual", is(false)))
                .andExpect(jsonPath("$.data.queueCapacity", is(50)));
    }

    @Test
    @DisplayName("测试事务生命周期钩子平滑降级与跨中间件消息传播协同（正常流）")
    void testTxAndMessageCooperate_NormalSuccess() throws Exception {
        TxMqCooperateRequest request = TxMqCooperateRequest.builder()
                .bizKey("ORDER-20261107-001")
                .payload("{\"amount\":199.00,\"sku\":\"SKU-BOOK-001\"}")
                .simulateFatalError(false)
                .build();

        mockMvc.perform(post("/sample/core/async/tx-and-mq")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code", is(200)))
                .andExpect(jsonPath("$.success", is(true)))
                .andExpect(jsonPath("$.data.bizKey", is("ORDER-20261107-001")))
                .andExpect(jsonPath("$.data.beforeCommitHookTriggered", is(true)))
                .andExpect(jsonPath("$.data.afterCommitHookTriggered", is(true)))
                .andExpect(jsonPath("$.data.completionStatus", is("COMMITTED")))
                .andExpect(jsonPath("$.data.fatalPoisonPill", is(false)))
                .andExpect(jsonPath("$.data.deserializedPayload", is("{\"amount\":199.00,\"sku\":\"SKU-BOOK-001\"}")))
                .andExpect(jsonPath("$.data.messageTraceId", notNullValue()))
                .andExpect(jsonPath("$.data.serializedHex", notNullValue()))
                .andExpect(jsonPath("$.data.messageContextActivated", is(true)))
                .andExpect(jsonPath("$.data.messageContextRestored", is(true)))
                .andExpect(jsonPath("$.data.consumedTraceId", notNullValue()))
                .andExpect(jsonPath("$.data.restoredTraceId", notNullValue()));
    }

    @Test
    @DisplayName("测试事务与消息协同场景下的不可自愈毒丸异常判定与死信阻断")
    void testTxAndMessageCooperate_FatalError() throws Exception {
        TxMqCooperateRequest request = TxMqCooperateRequest.builder()
                .bizKey("ORDER-20261107-POISON")
                .payload("{\"corrupted\":true}")
                .simulateFatalError(true)
                .build();

        mockMvc.perform(post("/sample/core/async/tx-and-mq")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code", is(200)))
                .andExpect(jsonPath("$.success", is(true)))
                .andExpect(jsonPath("$.data.bizKey", is("ORDER-20261107-POISON")))
                .andExpect(jsonPath("$.data.fatalPoisonPill", is(true)))
                .andExpect(jsonPath("$.data.fatalReason", containsString("不可自愈毒丸异常")));
    }

    @Test
    @DisplayName("测试异步批量任务强类型超时汇聚与受管线程池协同")
    void testBatchSupply_Success() throws Exception {
        AsyncBatchSupplyRequest request = AsyncBatchSupplyRequest.builder()
                .metrics(java.util.List.of("revenue", "gmv", "cost"))
                .build();

        mockMvc.perform(post("/sample/core/async/batch-supply")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code", is(200)))
                .andExpect(jsonPath("$.success", is(true)))
                .andExpect(jsonPath("$.data.totalTasks", is(3)))
                .andExpect(jsonPath("$.data.results[0]", is("METRIC_VALUE_REVENUE")))
                .andExpect(jsonPath("$.data.results[1]", is("METRIC_VALUE_GMV")))
                .andExpect(jsonPath("$.data.results[2]", is("METRIC_VALUE_COST")));
    }

    @Test
    @DisplayName("测试异步风控多维度并行评估且全部满足通过")
    void testRiskCheck_AllPassed() throws Exception {
        AsyncRiskCheckRequest request = AsyncRiskCheckRequest.builder()
                .scores(java.util.List.of(85, 90, 78))
                .safetyThreshold(60)
                .build();

        mockMvc.perform(post("/sample/core/async/risk-check")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code", is(200)))
                .andExpect(jsonPath("$.success", is(true)))
                .andExpect(jsonPath("$.data.allPassed", is(true)))
                .andExpect(jsonPath("$.data.totalRules", is(3)));
    }

    @Test
    @DisplayName("测试异步风控并行评估遭遇低分触发早期短路取消拦截")
    void testRiskCheck_ShortCircuit() throws Exception {
        AsyncRiskCheckRequest request = AsyncRiskCheckRequest.builder()
                .scores(java.util.List.of(90, 45, 95))
                .safetyThreshold(60)
                .build();

        mockMvc.perform(post("/sample/core/async/risk-check")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code", is(200)))
                .andExpect(jsonPath("$.success", is(true)))
                .andExpect(jsonPath("$.data.allPassed", is(false)))
                .andExpect(jsonPath("$.data.totalRules", is(3)));
    }
}
