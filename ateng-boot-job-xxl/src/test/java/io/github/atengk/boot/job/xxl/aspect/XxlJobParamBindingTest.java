package io.github.atengk.boot.job.xxl.aspect;

import com.xxl.job.core.context.XxlJobContext;
import io.github.atengk.boot.core.context.TenantContextHolder;
import io.github.atengk.boot.core.context.UserContextHolder;
import io.github.atengk.boot.core.exception.BusinessException;
import io.github.atengk.boot.job.xxl.annotation.AtengXxlJob;
import io.github.atengk.boot.job.xxl.enums.JobTenantMode;
import io.github.atengk.boot.job.xxl.model.ShardingContext;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.EnableAspectJAutoProxy;
import org.springframework.stereotype.Service;

import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * XXL-Job 方法级强类型 DTO 绑定、JSR-303 防卫与 ShardingContext 智能注入测试
 *
 * @author Ateng
 * @since 2026-10-31
 */
@SpringBootTest(classes = XxlJobParamBindingTest.TestConfig.class)
@DisplayName("XXL-Job 参数绑定与 JSR-303 防卫测试")
class XxlJobParamBindingTest {

    @Autowired
    private JobBindingTestService bindingService;

    @AfterEach
    void tearDown() {
        XxlJobContext.setXxlJobContext(null);
        TenantContextHolder.clear();
        UserContextHolder.clear();
        MDC.clear();
        bindingService.reset();
    }

    @Test
    @DisplayName("测试合法 JSON 格式入参：正确反序列化并绑定强类型 DTO，业务方法正常执行")
    void testValidDtoBinding() {
        String json = "{\"userId\":1001,\"username\":\"admin\",\"age\":25}";
        XxlJobContext context = new XxlJobContext(1001L, json, 1L, System.currentTimeMillis(), "test.log", 0, 1);
        XxlJobContext.setXxlJobContext(context);

        bindingService.executeValidDtoJob(null);

        assertThat(bindingService.isExecuted()).isTrue();
        UserSyncParam param = bindingService.getReceivedUserParam();
        assertThat(param).isNotNull();
        assertThat(param.getUserId()).isEqualTo(1001L);
        assertThat(param.getUsername()).isEqualTo("admin");
        assertThat(param.getAge()).isEqualTo(25);
    }

    @Test
    @DisplayName("测试 DTO 违规入参：JSR-303 格式校验拦截并 Fail-Fast，记录 handleFail 且业务逻辑绝不执行")
    void testInvalidDtoFailFast() {
        // username 为空串，违反 @NotBlank
        String invalidJson = "{\"userId\":1001,\"username\":\"\",\"age\":25}";
        XxlJobContext context = new XxlJobContext(1002L, invalidJson, 2L, System.currentTimeMillis(), "test.log", 0, 1);
        XxlJobContext.setXxlJobContext(context);

        assertThatThrownBy(() -> bindingService.executeValidDtoJob(null))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("用户名不能为空");

        // 业务逻辑严禁被执行
        assertThat(bindingService.isExecuted()).isFalse();
        assertThat(bindingService.getReceivedUserParam()).isNull();

        // XXL-Job 调度上下文状态已标记为失败
        assertThat(context.getHandleCode()).isEqualTo(XxlJobContext.HANDLE_CODE_FAIL);
        assertThat(context.getHandleMsg()).contains("JSR-303 参数校验失败");
        assertThat(context.getHandleMsg()).contains("用户名不能为空");
    }

    @Test
    @DisplayName("测试 DTO 缺省空入参：挂载 @Valid 的 DTO 为空时触发非空拦截与 Fail-Fast")
    void testNullDtoFailFast() {
        XxlJobContext context = new XxlJobContext(1003L, "", 3L, System.currentTimeMillis(), "test.log", 0, 1);
        XxlJobContext.setXxlJobContext(context);

        assertThatThrownBy(() -> bindingService.executeValidDtoJob(null))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("不能为空");

        assertThat(bindingService.isExecuted()).isFalse();
        assertThat(context.getHandleCode()).isEqualTo(XxlJobContext.HANDLE_CODE_FAIL);
    }

    @Test
    @DisplayName("测试畸形 JSON 语法入参：反序列化异常拦截并调用 handleFail，业务逻辑不执行")
    void testMalformedJsonFailFast() {
        XxlJobContext context = new XxlJobContext(1004L, "{invalid_json_format", 4L, System.currentTimeMillis(), "test.log", 0, 1);
        XxlJobContext.setXxlJobContext(context);

        assertThatThrownBy(() -> bindingService.executeValidDtoJob(null))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("反序列化失败");

        assertThat(bindingService.isExecuted()).isFalse();
        assertThat(context.getHandleCode()).isEqualTo(XxlJobContext.HANDLE_CODE_FAIL);
        assertThat(context.getHandleMsg()).contains("参数反序列化失败");
    }

    @Test
    @DisplayName("测试 ShardingContext 智能注入：自动提取分片序号与总分片数")
    void testShardingContextInjection() {
        XxlJobContext context = new XxlJobContext(1005L, "", 5L, System.currentTimeMillis(), "test.log", 2, 5);
        XxlJobContext.setXxlJobContext(context);

        bindingService.executeShardingJob(null);

        assertThat(bindingService.isExecuted()).isTrue();
        ShardingContext sharding = bindingService.getReceivedSharding();
        assertThat(sharding).isNotNull();
        assertThat(sharding.getShardIndex()).isEqualTo(2);
        assertThat(sharding.getShardTotal()).isEqualTo(5);
        assertThat(sharding.isSharded()).isTrue();
        assertThat(sharding.isFirst()).isFalse();
        assertThat(sharding.isLast()).isFalse();
        assertThat(sharding.match(2L)).isTrue();
        assertThat(sharding.match(7L)).isTrue();
        assertThat(sharding.match(3L)).isFalse();
    }

    @Test
    @DisplayName("测试多参数绑定 (DTO + ShardingContext)：两类参数皆准确解析注入")
    void testMultipleParamsDtoAndSharding() {
        String json = "{\"userId\":2002,\"username\":\"worker\",\"age\":30}";
        XxlJobContext context = new XxlJobContext(1006L, json, 6L, System.currentTimeMillis(), "test.log", 1, 3);
        XxlJobContext.setXxlJobContext(context);

        bindingService.executeMultiParamsJob(null, null);

        assertThat(bindingService.isExecuted()).isTrue();
        UserSyncParam user = bindingService.getReceivedUserParam();
        assertThat(user).isNotNull();
        assertThat(user.getUserId()).isEqualTo(2002L);
        assertThat(user.getUsername()).isEqualTo("worker");

        ShardingContext sharding = bindingService.getReceivedSharding();
        assertThat(sharding).isNotNull();
        assertThat(sharding.getShardIndex()).isEqualTo(1);
        assertThat(sharding.getShardTotal()).isEqualTo(3);
    }

    @Test
    @DisplayName("测试多参数颠倒顺序 (ShardingContext + DTO)：无论位置如何均能自适应正确注入")
    void testMultipleParamsShardingFirstThenDto() {
        String json = "{\"userId\":3003,\"username\":\"leader\",\"age\":40}";
        XxlJobContext context = new XxlJobContext(1007L, json, 7L, System.currentTimeMillis(), "test.log", 0, 2);
        XxlJobContext.setXxlJobContext(context);

        bindingService.executeShardingFirstJob(null, null);

        assertThat(bindingService.isExecuted()).isTrue();
        ShardingContext sharding = bindingService.getReceivedSharding();
        assertThat(sharding).isNotNull();
        assertThat(sharding.getShardIndex()).isEqualTo(0);
        assertThat(sharding.getShardTotal()).isEqualTo(2);
        assertThat(sharding.isFirst()).isTrue();

        UserSyncParam user = bindingService.getReceivedUserParam();
        assertThat(user).isNotNull();
        assertThat(user.getUserId()).isEqualTo(3003L);
        assertThat(user.getUsername()).isEqualTo("leader");
    }

    @Test
    @DisplayName("测试原生 String 参数与 ShardingContext 组合绑定")
    void testStringAndShardingParamBinding() {
        XxlJobContext context = new XxlJobContext(1008L, "custom-sync-code-888", 8L, System.currentTimeMillis(), "test.log", 0, 1);
        XxlJobContext.setXxlJobContext(context);

        bindingService.executeStringAndShardingJob(null, null);

        assertThat(bindingService.isExecuted()).isTrue();
        assertThat(bindingService.getReceivedStringParam()).isEqualTo("custom-sync-code-888");
        assertThat(bindingService.getReceivedSharding().getShardIndex()).isEqualTo(0);
    }

    @Test
    @DisplayName("测试从已反序列化的 DTO 探测并绑定 tenantId")
    void testTenantIdExtractedFromResolvedDto() {
        String json = "{\"tenantId\":\"tenant_corp_88\",\"username\":\"corp_admin\"}";
        XxlJobContext context = new XxlJobContext(1009L, json, 9L, System.currentTimeMillis(), "test.log", 0, 1);
        XxlJobContext.setXxlJobContext(context);

        bindingService.executeTenantDtoJob(null);

        assertThat(bindingService.isExecuted()).isTrue();
        assertThat(bindingService.getReceivedTenantInJob()).isEqualTo("tenant_corp_88");

        // 终态现场安全恢复
        assertThat(TenantContextHolder.getTenantId()).isNull();
    }

    @Test
    @DisplayName("测试单测中直接传递非空实参调用的兼容性")
    void testDirectMethodInvocationWithExplicitArgs() {
        UserSyncParam explicit = new UserSyncParam(8888L, "explicit-user", 28);
        ShardingContext explicitSharding = new ShardingContext(3, 10);

        bindingService.executeMultiParamsJob(explicit, explicitSharding);

        assertThat(bindingService.isExecuted()).isTrue();
        assertThat(bindingService.getReceivedUserParam().getUserId()).isEqualTo(8888L);
        assertThat(bindingService.getReceivedSharding().getShardIndex()).isEqualTo(3);
    }

    @Configuration(proxyBeanMethods = false)
    @EnableAspectJAutoProxy
    static class TestConfig {
        @Bean
        public XxlJobContextAspect xxlJobContextAspect() {
            return new XxlJobContextAspect();
        }

        @Bean
        public JobBindingTestService jobBindingTestService() {
            return new JobBindingTestService();
        }
    }

    @Service
    public static class JobBindingTestService {

        private final AtomicBoolean executed = new AtomicBoolean(false);
        private final AtomicReference<UserSyncParam> receivedUserParam = new AtomicReference<>();
        private final AtomicReference<ShardingContext> receivedSharding = new AtomicReference<>();
        private final AtomicReference<String> receivedStringParam = new AtomicReference<>();
        private final AtomicReference<String> receivedTenantInJob = new AtomicReference<>();

        public void reset() {
            executed.set(false);
            receivedUserParam.set(null);
            receivedSharding.set(null);
            receivedStringParam.set(null);
            receivedTenantInJob.set(null);
        }

        public boolean isExecuted() {
            return executed.get();
        }

        public UserSyncParam getReceivedUserParam() {
            return receivedUserParam.get();
        }

        public ShardingContext getReceivedSharding() {
            return receivedSharding.get();
        }

        public String getReceivedStringParam() {
            return receivedStringParam.get();
        }

        public String getReceivedTenantInJob() {
            return receivedTenantInJob.get();
        }

        @AtengXxlJob("validDtoJob")
        public void executeValidDtoJob(@Valid UserSyncParam param) {
            executed.set(true);
            receivedUserParam.set(param);
        }

        @AtengXxlJob("shardingJob")
        public void executeShardingJob(ShardingContext sharding) {
            executed.set(true);
            receivedSharding.set(sharding);
        }

        @AtengXxlJob("multiParamsJob")
        public void executeMultiParamsJob(@Valid UserSyncParam param, ShardingContext sharding) {
            executed.set(true);
            receivedUserParam.set(param);
            receivedSharding.set(sharding);
        }

        @AtengXxlJob("shardingFirstJob")
        public void executeShardingFirstJob(ShardingContext sharding, @Valid UserSyncParam param) {
            executed.set(true);
            receivedSharding.set(sharding);
            receivedUserParam.set(param);
        }

        @AtengXxlJob("strAndShardingJob")
        public void executeStringAndShardingJob(String taskCode, ShardingContext sharding) {
            executed.set(true);
            receivedStringParam.set(taskCode);
            receivedSharding.set(sharding);
        }

        @AtengXxlJob(value = "tenantDtoJob", tenantMode = JobTenantMode.PARAM)
        public void executeTenantDtoJob(@Valid TenantUserParam param) {
            executed.set(true);
            receivedTenantInJob.set(TenantContextHolder.getTenantId());
        }
    }

    public static class UserSyncParam {
        @NotNull(message = "用户ID不能为空")
        private Long userId;

        @NotBlank(message = "用户名不能为空")
        private String username;

        @Min(value = 1, message = "年龄必须大于0")
        private Integer age;

        public UserSyncParam() {
        }

        public UserSyncParam(Long userId, String username, Integer age) {
            this.userId = userId;
            this.username = username;
            this.age = age;
        }

        public Long getUserId() {
            return userId;
        }

        public void setUserId(Long userId) {
            this.userId = userId;
        }

        public String getUsername() {
            return username;
        }

        public void setUsername(String username) {
            this.username = username;
        }

        public Integer getAge() {
            return age;
        }

        public void setAge(Integer age) {
            this.age = age;
        }
    }

    public static class TenantUserParam {
        @NotBlank(message = "租户ID不能为空")
        private String tenantId;

        @NotBlank(message = "用户名不能为空")
        private String username;

        public TenantUserParam() {
        }

        public TenantUserParam(String tenantId, String username) {
            this.tenantId = tenantId;
            this.username = username;
        }

        public String getTenantId() {
            return tenantId;
        }

        public void setTenantId(String tenantId) {
            this.tenantId = tenantId;
        }

        public String getUsername() {
            return username;
        }

        public void setUsername(String username) {
            this.username = username;
        }
    }
}
