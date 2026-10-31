package io.github.atengk.boot.sample.job.snail;

import com.aizuda.snailjob.common.core.enums.StatusEnum;
import com.aizuda.snailjob.model.dto.ExecuteResult;
import io.github.atengk.boot.core.context.TenantContextHolder;
import io.github.atengk.boot.core.context.UserContextHolder;
import io.github.atengk.boot.core.model.Result;
import io.github.atengk.boot.job.snail.actuator.SnailJobEndpoint;
import io.github.atengk.boot.job.snail.actuator.SnailJobHealthIndicator;
import io.github.atengk.boot.job.snail.context.SnailJobContextHolder;
import io.github.atengk.boot.job.snail.drain.GracefulDrainStateMachine;
import io.github.atengk.boot.job.snail.helper.SnailJobTestHelper;
import io.github.atengk.boot.job.snail.helper.SnailJobTestHelper.SnailJobTestResult;
import io.github.atengk.boot.job.snail.helper.SnailJobTestHelper.SnailRetryTestResult;
import io.github.atengk.boot.job.snail.metrics.SnailJobMetricsBinder;
import io.github.atengk.boot.sample.job.snail.handler.AlertDemoJobHandler;
import io.github.atengk.boot.sample.job.snail.service.RetryDemoService;
import io.micrometer.core.instrument.MeterRegistry;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.actuate.health.Health;
import org.springframework.boot.actuate.health.Status;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * SnailJob 调度与重试双擎场景工程端到端自验集成测试 (100% 离线脱网沙箱)
 *
 * @author Ateng
 * @since 2026-10-31
 */
@SpringBootTest(classes = SampleJobSnailApplication.class, webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class SampleJobSnailIntegrationTest {

    @LocalServerPort
    private int port;

    @Autowired
    private TestRestTemplate restTemplate;

    @Autowired
    private SnailJobTestHelper testHelper;

    @Autowired
    private RetryDemoService retryDemoService;

    @Autowired
    private AlertDemoJobHandler alertDemoJobHandler;

    @Autowired
    private SnailJobEndpoint snailJobEndpoint;

    @Autowired
    private SnailJobHealthIndicator healthIndicator;

    @Autowired
    private GracefulDrainStateMachine drainStateMachine;

    @Autowired
    private SnailJobMetricsBinder metricsBinder;

    @Autowired
    private MeterRegistry meterRegistry;

    @AfterEach
    void tearDown() {
        SnailJobContextHolder.clear();
        TenantContextHolder.clear();
        UserContextHolder.clear();
        MDC.clear();
        alertDemoJobHandler.setFaultInjected(false);
        retryDemoService.resetAttemptCount();
    }

    @Test
    @DisplayName("1. 基础极简问候任务脱网触发实测 (带 MDC traceId 现场自洽)")
    void testSimpleGreetingJob() {
        SnailJobTestResult testResult = testHelper.triggerJob("simpleGreetingJob", null);

        assertThat(testResult).isNotNull();
        assertThat(testResult.isSuccess()).isTrue();
        assertThat(testResult.result()).isInstanceOf(ExecuteResult.class);
        ExecuteResult result = (ExecuteResult) testResult.result();
        assertThat(result.getStatus()).isEqualTo(StatusEnum.YES.getStatus());
        assertThat(result.getResult().toString()).contains("Greeting executed with traceId:");
        assertThat(SnailJobContextHolder.getContext()).isNull();
        assertThat(MDC.get("traceId")).isNull();
    }

    @Test
    @DisplayName("2. 超时守卫增强任务实测")
    void testTimeoutGuardedJob() {
        SnailJobTestResult testResult = testHelper.triggerJob("timeoutGuardedJob", null);

        assertThat(testResult).isNotNull();
        assertThat(testResult.isSuccess()).isTrue();
        ExecuteResult result = (ExecuteResult) testResult.result();
        assertThat(result.getStatus()).isEqualTo(StatusEnum.YES.getStatus());
        assertThat(result.getResult().toString()).isEqualTo("Timeout guarded job succeeded");
    }

    @Test
    @DisplayName("3. 强类型入参校验任务实测 - 正常参数通过")
    void testUserSyncParamValidationSuccess() {
        String jsonParam = "{\"batchNo\":\"BATCH-20261031\",\"batchSize\":100,\"departmentId\":\"DEPT-01\"}";
        SnailJobTestResult testResult = testHelper.triggerJob("userSyncJob", jsonParam);

        assertThat(testResult).isNotNull();
        assertThat(testResult.isSuccess()).isTrue();
        ExecuteResult result = (ExecuteResult) testResult.result();
        assertThat(result.getStatus()).isEqualTo(StatusEnum.YES.getStatus());
        assertThat(result.getResult().toString()).isEqualTo("Sync completed for batch BATCH-20261031");
    }

    @Test
    @DisplayName("4. 强类型入参校验任务实测 - 校验失败 Fail-Fast")
    void testUserSyncParamValidationFailFast() {
        // 批次为空，batchSize 小于 1
        String invalidJson = "{\"batchNo\":\"\",\"batchSize\":0}";
        SnailJobTestResult testResult = testHelper.triggerJob("userSyncJob", invalidJson);

        assertThat(testResult).isNotNull();
        assertThat(testResult.isSuccess()).isFalse();
        assertThat(testResult.throwable()).isNotNull();
        assertThat(testResult.throwable().getMessage()).containsAnyOf("不能为空", "大于0", "不合法");
    }

    @Test
    @DisplayName("5. 分布式广播分片流式批处理模板实测 (ShardingContext 过滤与统计)")
    void testShardingBatchJob() {
        // 模拟第 0 个分片 (共 2 个分片)
        SnailJobTestResult r0 = testHelper.triggerJob("shardingBatchJob", null, 0, 2);
        assertThat(r0.isSuccess()).isTrue();
        ExecuteResult result0 = (ExecuteResult) r0.result();
        assertThat(result0.getStatus()).isEqualTo(StatusEnum.YES.getStatus());
        assertThat(result0.getResult().toString()).contains("Sharding batch completed");

        // 模拟第 1 个分片 (共 2 个分片)
        SnailJobTestResult r1 = testHelper.triggerJob("shardingBatchJob", null, 1, 2);
        assertThat(r1.isSuccess()).isTrue();
        ExecuteResult result1 = (ExecuteResult) r1.result();
        assertThat(result1.getStatus()).isEqualTo(StatusEnum.YES.getStatus());
        assertThat(result1.getResult().toString()).contains("Sharding batch completed");
    }

    @Test
    @DisplayName("6. 多租户隔离调度任务三模实测 (IGNORE / PARAM / ITERATE)")
    void testTenantJobs() {
        // 模式一：全局忽略多租户
        SnailJobTestResult tr1 = testHelper.triggerJob("globalArchiveJob", null);
        assertThat(tr1.isSuccess()).isTrue();
        ExecuteResult r1 = (ExecuteResult) tr1.result();
        assertThat(r1.getStatus()).isEqualTo(StatusEnum.YES.getStatus());
        assertThat(r1.getResult().toString()).contains("ignoreTenant=true");

        // 模式二：单租户绑定
        SnailJobTestResult tr2 = testHelper.triggerJob("tenantScopedJob", null, 0, 1, "TENANT-999");
        assertThat(tr2.isSuccess()).isTrue();
        ExecuteResult r2 = (ExecuteResult) tr2.result();
        assertThat(r2.getStatus()).isEqualTo(StatusEnum.YES.getStatus());
        assertThat(r2.getResult().toString()).contains("tenant=TENANT-999");

        // 模式三：租户批量遍历执行
        SnailJobTestResult tr3 = testHelper.triggerJob("tenantBatchJob", null);
        assertThat(tr3.isSuccess()).isTrue();
        ExecuteResult r3 = (ExecuteResult) tr3.result();
        assertThat(r3.getStatus()).isEqualTo(StatusEnum.YES.getStatus());
        assertThat(r3.getResult().toString()).contains("Tenant batch finished: total=2, success=2");
    }

    @Test
    @DisplayName("7. 分布式协同重试实测 (声明式注解 / 编程式闭包 / 手动上报)")
    void testRetryDemoService() {
        // 7.1 离线反射驱动声明式重试成功
        SnailRetryTestResult res1 = testHelper.triggerRetry("orderPayRetry", "ORD-1001", 88.0);
        assertThat(res1.isSuccess()).isTrue();
        assertThat(res1.result().toString()).contains("PAY_SUCCESS:ORD-1001");

        // 7.2 模拟重试抛出异常
        SnailRetryTestResult failRes = testHelper.triggerRetry("orderPayRetry", "FAIL_ORDER", 88.0);
        assertThat(failRes.isSuccess()).isFalse();
        assertThat(failRes.throwable()).isNotNull();
        assertThat(failRes.throwable().getMessage()).contains("模拟支付网关网络超时");

        // 7.3 编程式重试闭包
        String res2 = retryDemoService.executeWithProgrammaticRetry("ORD-2002");
        assertThat(res2).isEqualTo("SUCCESS:ORD-2002");

        // 7.4 手动上报（脱网环境保底返回 true）
        boolean reported = retryDemoService.reportFailedOrder("ORD-3003");
        assertThat(reported).isTrue();
    }

    @Test
    @DisplayName("8. 告警防风暴聚合与自愈状态机端到端实测")
    void testAlertDemoJobStateMachine() {
        // 第一次失败：触发首发告警
        alertDemoJobHandler.setFaultInjected(true);
        SnailJobTestResult fail1 = testHelper.triggerJob("alertDemoJob", "FAIL");
        assertThat(fail1.isSuccess()).isTrue();
        ExecuteResult er1 = (ExecuteResult) fail1.result();
        assertThat(er1.getStatus()).isEqualTo(StatusEnum.NO.getStatus());

        // 第二次失败：进入静默窗口抑制
        ExecuteResult er2 = (ExecuteResult) testHelper.triggerJob("alertDemoJob", "FAIL").result();
        assertThat(er2.getStatus()).isEqualTo(StatusEnum.NO.getStatus());

        // 故障恢复：记录自愈现场并上报恢复
        alertDemoJobHandler.setFaultInjected(false);
        ExecuteResult recovery = (ExecuteResult) testHelper.triggerJob("alertDemoJob", "OK").result();
        assertThat(recovery.getStatus()).isEqualTo(StatusEnum.YES.getStatus());
    }

    @Test
    @DisplayName("9. Actuator 运维管控与健康探针端到端实测")
    void testActuatorEndpoints() {
        // 9.1 SnailJob 健康探针 (脱网环境下报告状态与连接详情)
        Health health = healthIndicator.health();
        assertThat(health).isNotNull();
        assertThat(health.getDetails()).containsKey("server");

        // 9.2 SnailJob 运维端点
        Map<String, Object> overview = snailJobEndpoint.listJobs();
        assertThat(overview).containsKeys("server", "enabled", "draining", "inFlightJobs", "inFlightRetries");

        // 9.3 验证平滑排空状态机初始未处于排空中
        assertThat(drainStateMachine.isDraining()).isFalse();

        // 9.4 通过 HTTP TestRestTemplate 访问 /actuator/snailjobs
        ResponseEntity<Map> snailJobResp = restTemplate.getForEntity("/actuator/snailjobs", Map.class);
        assertThat(snailJobResp.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(snailJobResp.getBody()).isNotNull();
        assertThat(snailJobResp.getBody()).containsKey("server");
    }

    @Test
    @DisplayName("10. Web 契约层 Controller 统一 Result<T> 响应实测")
    void testWebController() {
        // 10.1 离线触发接口
        ResponseEntity<Result<String>> triggerResp = restTemplate.exchange(
                "/sample/snail-job/trigger-offline?jobName=simpleGreetingJob",
                HttpMethod.POST,
                null,
                new ParameterizedTypeReference<>() {}
        );
        assertThat(triggerResp.getStatusCode()).isEqualTo(HttpStatus.OK);
        Result<String> triggerBody = triggerResp.getBody();
        assertThat(triggerBody).isNotNull();
        assertThat(triggerBody.getCode()).isEqualTo(200);
        assertThat(triggerBody.getData()).contains("simpleGreetingJob");

        // 10.2 重试接口
        ResponseEntity<Result<String>> retryResp = restTemplate.exchange(
                "/sample/snail-job/retry-order?orderId=WEB-ORD-01",
                HttpMethod.POST,
                null,
                new ParameterizedTypeReference<>() {}
        );
        assertThat(retryResp.getStatusCode()).isEqualTo(HttpStatus.OK);
        Result<String> retryBody = retryResp.getBody();
        assertThat(retryBody).isNotNull();
        assertThat(retryBody.getCode()).isEqualTo(200);
        assertThat(retryBody.getData()).contains("PAY_SUCCESS:WEB-ORD-01");

        // 10.3 状态汇总接口
        ResponseEntity<Result<Map<String, Object>>> statusResp = restTemplate.exchange(
                "/sample/snail-job/status",
                HttpMethod.GET,
                null,
                new ParameterizedTypeReference<>() {}
        );
        assertThat(statusResp.getStatusCode()).isEqualTo(HttpStatus.OK);
        Result<Map<String, Object>> statusBody = statusResp.getBody();
        assertThat(statusBody).isNotNull();
        assertThat(statusBody.getCode()).isEqualTo(200);
        assertThat(statusBody.getData()).containsKey("jobCount");
    }
}
