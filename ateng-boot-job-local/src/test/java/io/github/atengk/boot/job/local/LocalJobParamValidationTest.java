package io.github.atengk.boot.job.local;

import io.github.atengk.boot.core.exception.BusinessException;
import io.github.atengk.boot.job.local.annotation.LocalJob;
import io.github.atengk.boot.job.local.config.AtengJobLocalAutoConfiguration;
import io.github.atengk.boot.job.local.handler.MethodInvokingJobHandler;
import io.github.atengk.boot.job.local.model.JobContext;
import io.github.atengk.boot.job.local.model.JobResult;
import io.github.atengk.boot.job.local.template.LocalJobTemplate;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.stereotype.Component;

import java.lang.reflect.Method;
import java.util.Collections;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 强类型入参 DTO 绑定与 JSR-303 Fail-Fast 校验集成测试
 *
 * @author Ateng
 * @since 2026-11-01
 */
@SpringBootTest(classes = LocalJobParamValidationTest.TestConfig.class)
@DisplayName("LocalJob 强类型入参 DTO 绑定与 JSR-303 校验测试")
class LocalJobParamValidationTest {

    @Autowired
    private LocalJobTemplate jobTemplate;

    @Autowired
    private ParamJobTasks tasks;

    @Test
    @DisplayName("测试 DTO 单参数方法：合规入参自动绑定反序列化成功")
    void testValidParamBinding() {
        Map<String, Object> params = Map.of(
                "orderPrefix", "ORD",
                "batchSize", 50,
                "dryRun", true
        );
        JobResult result = jobTemplate.trigger("jobWithParamOnly", params);
        assertThat(result.isSuccess()).isTrue();
        assertThat(result.getMessage()).isEqualTo("Cleaned ORD, batch=50, dryRun=true");
        assertThat(tasks.lastReceivedParam).isNotNull();
        assertThat(tasks.lastReceivedParam.getOrderPrefix()).isEqualTo("ORD");
        assertThat(tasks.lastReceivedParam.getBatchSize()).isEqualTo(50);
        assertThat(tasks.lastReceivedParam.isDryRun()).isTrue();
    }

    @Test
    @DisplayName("测试 DTO 单参数方法：必填字段缺失触发 JSR-303 快速失败阻断")
    void testParamValidationFailFastMissingRequiredField() {
        // 缺少 @NotBlank 的 orderPrefix
        Map<String, Object> params = Map.of(
                "batchSize", 50
        );
        JobResult result = jobTemplate.trigger("jobWithParamOnly", params);
        assertThat(result.isSuccess()).isFalse();
        assertThat(result.getMessage()).contains("订单前缀不能为空");
    }

    @Test
    @DisplayName("测试 DTO 单参数方法：数值越界触发 JSR-303 快速失败阻断")
    void testParamValidationFailFastInvalidValue() {
        // batchSize 为负数，违反 @Min(1)
        Map<String, Object> params = Map.of(
                "orderPrefix", "ORD",
                "batchSize", -5
        );
        JobResult result = jobTemplate.trigger("jobWithParamOnly", params);
        assertThat(result.isSuccess()).isFalse();
        assertThat(result.getMessage()).contains("批次大小必须大于0");
    }

    @Test
    @DisplayName("测试双参数签名 (DTO, JobContext)：同时注入 DTO 与上下文并正常记录日志")
    void testSignatureParamAndContext() {
        Map<String, Object> params = Map.of(
                "orderPrefix", "CTX1",
                "batchSize", 100
        );
        JobResult result = jobTemplate.trigger("jobWithParamAndContext", params);
        assertThat(result.isSuccess()).isTrue();
        assertThat(result.getMessage()).isEqualTo("ParamAndContext OK: CTX1");
        assertThat(tasks.lastReceivedContext).isNotNull();
        assertThat(tasks.lastReceivedContext.getLogs()).anyMatch(log -> log.contains("jobWithParamAndContext executed for CTX1"));
    }

    @Test
    @DisplayName("测试双参数签名反序 (JobContext, DTO)：自适应识别位置并注入成功")
    void testSignatureContextAndParam() {
        Map<String, Object> params = Map.of(
                "orderPrefix", "CTX2",
                "batchSize", 20
        );
        JobResult result = jobTemplate.trigger("jobWithContextAndParam", params);
        assertThat(result.isSuccess()).isTrue();
        assertThat(result.getMessage()).isEqualTo("ContextAndParam OK: CTX2");
    }

    @Test
    @DisplayName("测试无参方法签名：正常反射调度")
    void testSignatureNoArgs() {
        JobResult result = jobTemplate.trigger("jobWithNoArgs", Collections.emptyMap());
        assertThat(result.isSuccess()).isTrue();
        assertThat(result.getMessage()).isEqualTo("NoArgsSuccess");
    }

    @Test
    @DisplayName("测试 JobContext.bind 编程式调用：参数合规成功反序列化")
    void testProgrammaticContextBindSuccess() {
        Map<String, Object> params = Map.of(
                "orderPrefix", "PROG",
                "batchSize", 30
        );
        JobResult result = jobTemplate.trigger("jobWithContextProgrammatic", params);
        assertThat(result.isSuccess()).isTrue();
        assertThat(result.getMessage()).isEqualTo("Programmatic OK: PROG");
    }

    @Test
    @DisplayName("测试 JobContext.bind 编程式调用：校验失败抛出 BusinessException 快速失败")
    void testProgrammaticContextBindValidationFailure() {
        Map<String, Object> params = Map.of(
                "orderPrefix", "",
                "batchSize", 0
        );
        JobResult result = jobTemplate.trigger("jobWithContextProgrammatic", params);
        assertThat(result.isSuccess()).isFalse();
        assertThat(result.getMessage()).containsAnyOf("订单前缀不能为空", "批次大小必须大于0");
    }

    @Test
    @DisplayName("测试 JSON 字符串参数反序列化：单字符串入参自动识别解析并校验")
    void testJsonStringBinding() {
        String jsonPayload = "{\"orderPrefix\":\"JSON_ORD\",\"batchSize\":99,\"dryRun\":false}";
        Map<String, Object> params = Map.of("payload", jsonPayload);
        JobResult result = jobTemplate.trigger("jobWithParamOnly", params);
        assertThat(result.isSuccess()).isTrue();
        assertThat(result.getMessage()).isEqualTo("Cleaned JSON_ORD, batch=99, dryRun=false");
    }

    @Test
    @DisplayName("测试非法方法签名防卫：参数超出 2 个或 2 个均为上下文/DTO 时抛出 IllegalArgumentException")
    void testInvalidMethodSignatures() {
        DummyService dummy = new DummyService();

        // 3 个参数
        Method m3 = findMethod(DummyService.class, "invalidThreeParams");
        assertThatThrownBy(() -> new MethodInvokingJobHandler(dummy, m3))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("must have at most 2 parameters");

        // 2 个参数但均无 JobContext
        Method m2NoContext = findMethod(DummyService.class, "invalidTwoDtos");
        assertThatThrownBy(() -> new MethodInvokingJobHandler(dummy, m2NoContext))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("must contain one JobContext and one custom DTO");

        // 2 个参数均为 JobContext
        Method m2BothContext = findMethod(DummyService.class, "invalidTwoContexts");
        assertThatThrownBy(() -> new MethodInvokingJobHandler(dummy, m2BothContext))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("must contain one JobContext and one custom DTO");
    }

    private Method findMethod(Class<?> clazz, String name) {
        for (Method m : clazz.getDeclaredMethods()) {
            if (m.getName().equals(name)) {
                return m;
            }
        }
        throw new IllegalArgumentException("Method not found: " + name);
    }

    @Configuration
    @Import({AtengJobLocalAutoConfiguration.class, ParamJobTasks.class})
    static class TestConfig {
    }

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    public static class OrderCleanupParam {
        @NotBlank(message = "订单前缀不能为空")
        private String orderPrefix;

        @Min(value = 1, message = "批次大小必须大于0")
        private int batchSize = 10;

        private boolean dryRun;
    }

    @Component
    public static class ParamJobTasks {

        volatile OrderCleanupParam lastReceivedParam;
        volatile JobContext lastReceivedContext;

        @LocalJob(name = "jobWithParamOnly", fixedDelay = "1h", initialDelay = "1h")
        public JobResult jobWithParamOnly(OrderCleanupParam param) {
            this.lastReceivedParam = param;
            return JobResult.success("Cleaned " + param.getOrderPrefix() + ", batch=" + param.getBatchSize() + ", dryRun=" + param.isDryRun());
        }

        @LocalJob(name = "jobWithParamAndContext", fixedDelay = "1h", initialDelay = "1h")
        public JobResult jobWithParamAndContext(OrderCleanupParam param, JobContext context) {
            this.lastReceivedParam = param;
            this.lastReceivedContext = context;
            context.log("jobWithParamAndContext executed for " + param.getOrderPrefix());
            return JobResult.success("ParamAndContext OK: " + param.getOrderPrefix());
        }

        @LocalJob(name = "jobWithContextAndParam", fixedDelay = "1h", initialDelay = "1h")
        public JobResult jobWithContextAndParam(JobContext context, OrderCleanupParam param) {
            this.lastReceivedParam = param;
            this.lastReceivedContext = context;
            return JobResult.success("ContextAndParam OK: " + param.getOrderPrefix());
        }

        @LocalJob(name = "jobWithNoArgs", fixedDelay = "1h", initialDelay = "1h")
        public String jobWithNoArgs() {
            return "NoArgsSuccess";
        }

        @LocalJob(name = "jobWithContextProgrammatic", fixedDelay = "1h", initialDelay = "1h")
        public JobResult jobWithContextProgrammatic(JobContext context) {
            OrderCleanupParam param = context.bind(OrderCleanupParam.class);
            return JobResult.success("Programmatic OK: " + param.getOrderPrefix());
        }
    }

    @Test
    @DisplayName("测试 JobContext.bind 反射异常时保留原始异常因果链")
    void testBindPreservesCauseOnInstantiationFailure() {
        JobContext context = JobContext.builder()
                .jobName("testJob")
                .params(Map.of("foo", "bar"))
                .build();
        assertThatThrownBy(() -> context.bind(UninstantiableParam.class))
                .isInstanceOf(BusinessException.class)
                .hasCauseInstanceOf(Exception.class);
    }

    public static abstract class UninstantiableParam {
    }

    public static class DummyService {
        public void invalidThreeParams(OrderCleanupParam p1, JobContext ctx, String other) {}
        public void invalidTwoDtos(OrderCleanupParam p1, OrderCleanupParam p2) {}
        public void invalidTwoContexts(JobContext c1, JobContext c2) {}
    }
}
