package io.github.atengk.boot.sample;

import io.github.atengk.boot.core.context.TenantContextHolder;
import io.github.atengk.boot.core.context.UserContextHolder;
import io.github.atengk.boot.sample.entity.UserEntity;
import io.github.atengk.boot.sample.mapper.UserMapper;
import io.github.atengk.boot.sample.service.AsyncSampleService;
import io.github.atengk.boot.sample.service.AsyncSampleService.AsyncExecutionResult;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledForJreRange;
import org.junit.jupiter.api.condition.JRE;
import org.slf4j.MDC;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;
import org.springframework.core.task.SimpleAsyncTaskExecutor;
import org.springframework.test.web.servlet.MockMvc;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 虚拟线程运行时与四维上下文传播最高接缝端到端集成测试
 * 验证在开启 spring.threads.virtual.enabled=true 且运行于 JDK 21+ 时：
 * 1. 容器自适应注册 SimpleAsyncTaskExecutor 并配置专属虚拟线程前缀 ateng-vt-async-；
 * 2. @Async 异步调用无缝调度于 Java 原生虚拟线程（isVirtual() == true）；
 * 3. MDC traceId、UserContextHolder 与 TenantContextHolder 四维快照全链路透传并触发审计填充；
 * 4. 异步任务执行后严格在 finally 块中清理现场，杜绝上下文泄漏；
 * 5. Web 请求边界与虚拟线程协同正常运转；
 * 6. 支撑高并发虚拟线程并发调度与隔离。
 *
 * @author Ateng
 * @since 2026-10-17
 */
@SpringBootTest(properties = "spring.threads.virtual.enabled=true")
@AutoConfigureMockMvc
@DisplayName("虚拟线程运行时与四维上下文传播端到端集成测试")
@EnabledForJreRange(min = JRE.JAVA_21)
class SampleVirtualThreadIntegrationTest {

    @Autowired
    private ApplicationContext applicationContext;

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private AsyncSampleService asyncSampleService;

    @Autowired
    private UserMapper userMapper;

    @BeforeEach
    void setUp() {
        MDC.clear();
        UserContextHolder.clear();
        TenantContextHolder.clear();
    }

    @AfterEach
    void tearDown() {
        MDC.clear();
        UserContextHolder.clear();
        TenantContextHolder.clear();
    }

    @Test
    @DisplayName("1. 执行器拓扑契约：开启虚拟线程后容器成功自适应装配 SimpleAsyncTaskExecutor 并绑定专属前缀")
    void shouldAutoConfigureVirtualThreadTaskExecutorInContainer() {
        assertThat(applicationContext.containsBean("taskExecutor")).isTrue();
        assertThat(applicationContext.containsBean("applicationTaskExecutor")).isTrue();

        Object taskExecutor = applicationContext.getBean("taskExecutor");
        assertThat(taskExecutor).isInstanceOf(SimpleAsyncTaskExecutor.class);

        SimpleAsyncTaskExecutor simpleAsyncTaskExecutor = (SimpleAsyncTaskExecutor) taskExecutor;
        assertThat(simpleAsyncTaskExecutor.getThreadNamePrefix()).isEqualTo("ateng-vt-async-");
    }

    @Test
    @DisplayName("2. @Async 虚拟线程执行与四维上下文透传：断言 isVirtual=true、前缀匹配、MDC/User/Tenant/IgnoreTenant 完整还原与审计入库")
    void shouldExecuteOnVirtualThreadWithFourDimensionalContextPropagationAndAudit() throws Exception {
        String testUser = "vt-admin-8888";
        String testTrace = "trace-vt-77777";
        String testTenant = "tenant-vt-001";

        UserContextHolder.set(testUser);
        MDC.put("traceId", testTrace);
        TenantContextHolder.setTenantId(testTenant);

        UserEntity user = new UserEntity();
        user.setName("虚拟线程测试用户");
        user.setAge(25);
        user.setEmail("vt_user@example.com");

        CompletableFuture<AsyncExecutionResult> future = asyncSampleService.saveUserAsync(user);
        AsyncExecutionResult executionResult = future.get(5, TimeUnit.SECONDS);

        // 1. 核心断言：工作线程确为 Java 21+ 原生虚拟线程
        assertThat(executionResult.isVirtual()).isTrue();

        // 2. 核心断言：线程名称符合专属规范前缀 ateng-vt-async-
        assertThat(executionResult.threadName()).startsWith("ateng-vt-async-");

        // 3. 核心断言：四维上下文快照（MDC、UserContext 与 TenantContext）在虚拟线程内完整还原
        assertThat(executionResult.traceId()).isEqualTo(testTrace);
        assertThat(executionResult.userId()).isEqualTo(testUser);
        assertThat(executionResult.tenantId()).isEqualTo(testTenant);
        assertThat(executionResult.ignoreTenant()).isFalse();

        // 4. 核心断言：数据持久层审计无缝识别异步用户身份，createBy 为当前登录人
        UserEntity stored = userMapper.selectById(executionResult.entityId());
        assertThat(stored).isNotNull();
        assertThat(stored.getCreateBy()).isEqualTo(testUser);
        assertThat(stored.getUpdateBy()).isEqualTo(testUser);
    }

    @Test
    @DisplayName("3. 嵌套忽略租户四维上下文透传：断言 runWithoutTenant 闭包下异步虚拟线程正确捕获 ignoreTenant=true")
    void shouldPropagateIgnoreTenantContextToVirtualThread() throws Exception {
        String testUser = "vt-system-auditor";
        String testTrace = "trace-vt-ignore-tenant";
        String testTenant = "tenant-global";

        UserContextHolder.set(testUser);
        MDC.put("traceId", testTrace);
        TenantContextHolder.setTenantId(testTenant);

        AtomicReference<AsyncExecutionResult> resultRef = new AtomicReference<>();

        TenantContextHolder.runWithoutTenant(() -> {
            UserEntity user = new UserEntity();
            user.setName("全局扫描用户");
            user.setAge(35);
            user.setEmail("global_scan@example.com");

            try {
                CompletableFuture<AsyncExecutionResult> future = asyncSampleService.saveUserAsync(user);
                resultRef.set(future.get(5, TimeUnit.SECONDS));
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        });

        AsyncExecutionResult executionResult = resultRef.get();
        assertThat(executionResult).isNotNull();
        assertThat(executionResult.isVirtual()).isTrue();
        assertThat(executionResult.tenantId()).isEqualTo(testTenant);
        assertThat(executionResult.ignoreTenant()).isTrue();
    }

    @Test
    @DisplayName("4. Web 传输层与虚拟线程协同：在虚拟线程运行时执行 HTTP 请求与分页查询")
    void shouldCoordinateWebLayerWithVirtualThreads() throws Exception {
        mockMvc.perform(get("/api/users/page")
                        .param("current", "1")
                        .param("size", "5"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data.records").isArray());
    }

    @Test
    @DisplayName("5. 高并发虚拟线程并发调度：瞬时多任务并发执行，断言各自独立拥有专属虚拟线程且四维上下文互不串扰")
    void shouldConcurrentlyExecuteMultipleVirtualThreadsWithoutContextInterference() throws Exception {
        int taskCount = 20;
        List<CompletableFuture<AsyncExecutionResult>> futures = new ArrayList<>(taskCount);

        for (int i = 0; i < taskCount; i++) {
            String userToken = "concurrent-user-" + i;
            String traceToken = "concurrent-trace-" + i;
            String tenantToken = "tenant-" + (i % 3);

            UserContextHolder.set(userToken);
            MDC.put("traceId", traceToken);
            TenantContextHolder.setTenantId(tenantToken);

            UserEntity user = new UserEntity();
            user.setName("并发用户-" + i);
            user.setAge(20 + i);
            user.setEmail("concurrent_" + i + "@example.com");

            futures.add(asyncSampleService.saveUserAsync(user));
        }

        // 主线程清理现场
        UserContextHolder.clear();
        MDC.clear();
        TenantContextHolder.clear();

        for (int i = 0; i < taskCount; i++) {
            AsyncExecutionResult result = futures.get(i).get(5, TimeUnit.SECONDS);
            assertThat(result.isVirtual()).isTrue();
            assertThat(result.threadName()).startsWith("ateng-vt-async-");
            assertThat(result.userId()).isEqualTo("concurrent-user-" + i);
            assertThat(result.traceId()).isEqualTo("concurrent-trace-" + i);
            assertThat(result.tenantId()).isEqualTo("tenant-" + (i % 3));

            UserEntity stored = userMapper.selectById(result.entityId());
            assertThat(stored.getCreateBy()).isEqualTo("concurrent-user-" + i);
        }
    }
}
