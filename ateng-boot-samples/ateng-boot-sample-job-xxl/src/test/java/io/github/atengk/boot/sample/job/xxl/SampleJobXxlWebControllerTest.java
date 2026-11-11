package io.github.atengk.boot.sample.job.xxl;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.hasItem;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * XXL-Job 样例工程 Web 领域控制器自动化契约测试套件
 *
 * <p>依托 {@link MockMvc} 对样例工程暴露的 4 大高内聚领域 Controller 进行全真契约测试，
 * 验证 HTTP 状态码 200、强类型 {@code Result<T>} 返回、JSR-303 Fail-Fast 校验拦截与各项交互端点。</p>
 *
 * @author Ateng
 * @since 2026-11-11
 */
@SpringBootTest(classes = SampleJobXxlApplication.class, webEnvironment = SpringBootTest.WebEnvironment.MOCK)
@AutoConfigureMockMvc
class SampleJobXxlWebControllerTest {

    private static HttpServer mockAdminServer;
    private static int mockAdminPort;

    @Autowired
    private MockMvc mockMvc;

    @BeforeAll
    static void startMockAdmin() throws Exception {
        String activeProfile = System.getProperty("spring.profiles.active", "");
        if (!activeProfile.contains("local")) {
            mockAdminServer = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            mockAdminPort = mockAdminServer.getAddress().getPort();
            mockAdminServer.createContext("/api", new MockAdminDispatcher());
            mockAdminServer.setExecutor(null);
            mockAdminServer.start();

            System.setProperty("ateng.boot.job.xxl.admin-addresses", "http://127.0.0.1:" + mockAdminPort);
            System.setProperty("ateng.boot.job.xxl.port", "0");
        }
    }

    @AfterAll
    static void stopMockAdmin() {
        if (mockAdminServer != null) {
            mockAdminServer.stop(0);
        }
    }

    @Test
    @DisplayName("控制器测试 1：XxlJobTriggerController 任务手动触发与参数校验")
    void testTriggerControllerEndpoints() throws Exception {
        // 1. POST /sample/job-xxl/trigger/warmup 系统预热
        mockMvc.perform(post("/sample/job-xxl/trigger/warmup"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data.success").value(true));

        // 2. POST /sample/job-xxl/trigger/user-sync 合法参数同步
        String validJson = "{\"batchNo\":\"BATCH-WEB-001\",\"batchSize\":300,\"tenantId\":\"T-WEB-01\"}";
        mockMvc.perform(post("/sample/job-xxl/trigger/user-sync")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(validJson))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data.success").value(true));

        // 3. POST /sample/job-xxl/trigger/user-sync 非法参数 JSR-303 Fail-Fast 快速失败拦截
        String invalidJson = "{\"batchNo\":\"\",\"batchSize\":9999}";
        mockMvc.perform(post("/sample/job-xxl/trigger/user-sync")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(invalidJson))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(400))
                .andExpect(jsonPath("$.success").value(false));

        // 4. POST /sample/job-xxl/trigger/sharding-batch 分片广播批处理
        mockMvc.perform(post("/sample/job-xxl/trigger/sharding-batch")
                        .param("shardIndex", "0")
                        .param("shardTotal", "2"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data.success").value(true));

        // 5. POST /sample/job-xxl/trigger/reconciliation 订单对账正常平账
        mockMvc.perform(post("/sample/job-xxl/trigger/reconciliation")
                        .param("hasDiff", "false"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data.success").value(true));

        // 6. POST /sample/job-xxl/trigger/reconciliation 订单对账模拟差错告警
        mockMvc.perform(post("/sample/job-xxl/trigger/reconciliation")
                        .param("hasDiff", "true"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data.success").value(false))
                .andExpect(jsonPath("$.data.errorMessage").value(containsString("差错调账告警")));

        // 7. POST /sample/job-xxl/trigger/timeout-archive 历史归档超时降级
        mockMvc.perform(post("/sample/job-xxl/trigger/timeout-archive"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data.success").value(true));

        // 8. POST /sample/job-xxl/trigger/tenant/global 租户全局归档
        mockMvc.perform(post("/sample/job-xxl/trigger/tenant/global"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data.success").value(true));

        // 9. POST /sample/job-xxl/trigger/tenant/scoped 单租户隔离账单
        mockMvc.perform(post("/sample/job-xxl/trigger/tenant/scoped")
                        .param("tenantId", "TENANT-999"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data.success").value(true));

        // 10. POST /sample/job-xxl/trigger/tenant/parallel 多租户并发遍历
        mockMvc.perform(post("/sample/job-xxl/trigger/tenant/parallel"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data.success").value(true));
    }

    @Test
    @DisplayName("控制器测试 2：XxlJobGovernorController 节点级运行时动态静默与恢复")
    void testGovernorControllerEndpoints() throws Exception {
        String handlerName = "pausableTaskJob";

        // 1. POST /sample/job-xxl/governor/pause/{handler} 暂停任务
        mockMvc.perform(post("/sample/job-xxl/governor/pause/" + handlerName))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data").value(true));

        // 2. GET /sample/job-xxl/governor/paused-handlers 巡检静默清单
        mockMvc.perform(get("/sample/job-xxl/governor/paused-handlers"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data", hasItem(handlerName)));

        // 3. POST /sample/job-xxl/governor/resume/{handler} 恢复任务
        mockMvc.perform(post("/sample/job-xxl/governor/resume/" + handlerName))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data").value(true));
    }

    @Test
    @DisplayName("控制器测试 3：XxlJobAdminController 调度中心 OpenAPI 编程式运维")
    void testAdminControllerEndpoints() throws Exception {
        // 1. POST /sample/job-xxl/admin/jobs 注册新任务 (合规)
        String addJson = "{\"jobGroup\":1,\"name\":\"动态报表任务\",\"executorHandler\":\"reportJob\",\"scheduleType\":\"CRON\",\"scheduleConf\":\"0 0 3 * * ?\"}";
        mockMvc.perform(post("/sample/job-xxl/admin/jobs")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(addJson))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data").value(101));

        // 2. POST /sample/job-xxl/admin/jobs 注册新任务 (缺失名称，JSR-303 快速拦截)
        String invalidAddJson = "{\"jobGroup\":1,\"name\":\"\",\"executorHandler\":\"\"}";
        mockMvc.perform(post("/sample/job-xxl/admin/jobs")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(invalidAddJson))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(400))
                .andExpect(jsonPath("$.success").value(false));

        // 3. POST /sample/job-xxl/admin/jobs/{jobId}/start 启动任务
        mockMvc.perform(post("/sample/job-xxl/admin/jobs/101/start"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data").value(true));

        // 4. POST /sample/job-xxl/admin/jobs/{jobId}/stop 停止任务
        mockMvc.perform(post("/sample/job-xxl/admin/jobs/101/stop"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data").value(true));

        // 5. POST /sample/job-xxl/admin/jobs/{jobId}/trigger 即时触发任务
        mockMvc.perform(post("/sample/job-xxl/admin/jobs/101/trigger")
                        .param("param", "web-trigger-test"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data").value(true));

        // 6. DELETE /sample/job-xxl/admin/jobs/{jobId} 删除任务
        mockMvc.perform(delete("/sample/job-xxl/admin/jobs/101"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data").value(true));
    }

    @Test
    @DisplayName("控制器测试 4：XxlJobDeadLetterController 死信切片回查、重放与清理")
    void testDeadLetterControllerEndpoints() throws Exception {
        // 先触发一次分片广播任务以生成死信切片
        mockMvc.perform(post("/sample/job-xxl/trigger/sharding-batch"))
                .andExpect(status().isOk());

        // 1. GET /sample/job-xxl/dead-letter/list 查询死信列表
        mockMvc.perform(get("/sample/job-xxl/dead-letter/list"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data").isArray());

        // 2. POST /sample/job-xxl/dead-letter/replay 受控重放死信
        mockMvc.perform(post("/sample/job-xxl/dead-letter/replay"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200));

        // 3. DELETE /sample/job-xxl/dead-letter/clear 清理死信队列
        mockMvc.perform(delete("/sample/job-xxl/dead-letter/clear"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data").value(true));
    }

    private static class MockAdminDispatcher implements HttpHandler {
        private static final String SUCCESS_RESPONSE = "{\"code\":200,\"msg\":\"Success\",\"data\":\"101\"}";

        @Override
        public void handle(HttpExchange exchange) throws IOException {
            byte[] responseBytes = SUCCESS_RESPONSE.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json; charset=utf-8");
            exchange.sendResponseHeaders(200, responseBytes.length);
            try (OutputStream os = exchange.getResponseBody()) {
                os.write(responseBytes);
            }
        }
    }
}
