package io.github.atengk.boot.sample.job.xxl;

import io.github.atengk.boot.sample.job.xxl.interceptor.CanaryAuditJobInterceptor;
import io.github.atengk.boot.sample.job.xxl.mock.EmbeddedMockXxlJobAdmin;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.greaterThanOrEqualTo;
import static org.hamcrest.Matchers.hasItem;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * XXL-Job 样例工程 Web 领域控制器自动化契约测试套件
 *
 * <p>依托 {@link MockMvc} 与容器受管的 {@code @Profile("!local")} {@link EmbeddedMockXxlJobAdmin}
 * 对样例工程暴露的 4 大高内聚领域 Controller 进行全真契约测试，
 * 验证 HTTP 状态码 200、强类型 {@code Result<T>} 返回、JSR-303 Fail-Fast 校验拦截与全特性交互端点。</p>
 *
 * @author Ateng
 * @since 2026-11-11
 */
@SpringBootTest(
        classes = SampleJobXxlApplication.class,
        webEnvironment = SpringBootTest.WebEnvironment.MOCK,
        properties = "ateng.boot.job.xxl.port=0"
)
@AutoConfigureMockMvc
class SampleJobXxlWebControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private EmbeddedMockXxlJobAdmin embeddedMockAdmin;

    @Autowired
    private CanaryAuditJobInterceptor canaryInterceptor;

    @Test
    @DisplayName("控制器测试 1：XxlJobTriggerController 全场景触发、重试、灰度与并发分片端点")
    void testTriggerControllerEndpoints() throws Exception {
        assertThat(embeddedMockAdmin.isRunning()).isTrue();
        assertThat(embeddedMockAdmin.getPort()).isPositive();

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

        // 5. POST /sample/job-xxl/trigger/sharding-parallel 并发分片批处理（派生视图 + 全局 BatchFailureHandler SPI）
        mockMvc.perform(delete("/sample/job-xxl/dead-letter/clear"))
                .andExpect(status().isOk());
        mockMvc.perform(post("/sample/job-xxl/trigger/sharding-parallel")
                        .param("shardIndex", "0")
                        .param("shardTotal", "2"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data.success").value(true))
                .andExpect(jsonPath("$.data.handleMsg").value(containsString("并发分片批处理完成")));
        mockMvc.perform(get("/sample/job-xxl/dead-letter/list"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.length()").value(greaterThanOrEqualTo(1)));

        // 6. POST /sample/job-xxl/trigger/reconciliation 订单对账正常平账
        mockMvc.perform(post("/sample/job-xxl/trigger/reconciliation")
                        .param("hasDiff", "false"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data.success").value(true));

        // 7. POST /sample/job-xxl/trigger/reconciliation 订单对账模拟差错告警
        mockMvc.perform(post("/sample/job-xxl/trigger/reconciliation")
                        .param("hasDiff", "true"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data.success").value(false))
                .andExpect(jsonPath("$.data.errorMessage").value(containsString("差错调账告警")));

        // 8. POST /sample/job-xxl/trigger/timeout-archive 历史归档超时降级
        mockMvc.perform(post("/sample/job-xxl/trigger/timeout-archive"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data.success").value(true));

        // 9. POST /sample/job-xxl/trigger/canary 灰度拦截跳过与准入放行（消费 jobId / logId 元数据）
        canaryInterceptor.clear();
        mockMvc.perform(post("/sample/job-xxl/trigger/canary")
                        .param("skip", "true"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data.success").value(true))
                .andExpect(jsonPath("$.data.handleMsg").value(containsString("[INTERCEPTED]")));
        mockMvc.perform(post("/sample/job-xxl/trigger/canary")
                        .param("skip", "false"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data.success").value(true));
        assertThat(canaryInterceptor.getLastCapturedMeta()).isNotNull();
        assertThat(canaryInterceptor.getLastCapturedMeta().jobHandler()).isEqualTo("canaryNormalJob");
        assertThat(canaryInterceptor.getLastCapturedMeta().logId()).isPositive();

        // 10. POST /sample/job-xxl/trigger/retry 独立单注解 @AtengXxlJob 本地退避重试自愈与耗尽路径
        mockMvc.perform(post("/sample/job-xxl/trigger/retry")
                        .param("exhaust", "false"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data.success").value(true))
                .andExpect(jsonPath("$.data.handleMsg").value(containsString("自愈成功")));
        mockMvc.perform(post("/sample/job-xxl/trigger/retry")
                        .param("exhaust", "true"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data.success").value(false))
                .andExpect(jsonPath("$.data.errorMessage").value(containsString("持续不可用")));

        // 11. POST /sample/job-xxl/trigger/tenant/global 租户全局归档
        mockMvc.perform(post("/sample/job-xxl/trigger/tenant/global"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data.success").value(true));

        // 12. POST /sample/job-xxl/trigger/tenant/scoped 单租户隔离账单
        mockMvc.perform(post("/sample/job-xxl/trigger/tenant/scoped")
                        .param("tenantId", "TENANT-999"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data.success").value(true));

        // 13. POST /sample/job-xxl/trigger/tenant/parallel 多租户并发遍历
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

        // 2.5 PUT /sample/job-xxl/admin/jobs 更新已有任务配置 (合规与 JSR-303 校验拦截)
        String updateJson = "{\"id\":101,\"name\":\"动态报表任务-已更新\",\"executorHandler\":\"reportJob\",\"scheduleType\":\"CRON\",\"scheduleConf\":\"0 0 4 * * ?\"}";
        mockMvc.perform(put("/sample/job-xxl/admin/jobs")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(updateJson))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data").value(true));

        String invalidUpdateJson = "{\"id\":0,\"name\":\"\",\"executorHandler\":\"\"}";
        mockMvc.perform(put("/sample/job-xxl/admin/jobs")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(invalidUpdateJson))
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
}
