package io.github.atengk.boot.sample.core;

import io.github.atengk.boot.core.context.TenantContextHolder;
import io.github.atengk.boot.core.context.UserContextHolder;
import io.github.atengk.boot.sample.core.service.CoreReportExportScenarioService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 业务专属受管异步线程池与舱壁隔离场景端到端集成测试
 *
 * @author Ateng
 * @since 2026-10-22
 */
@SpringBootTest
@DisplayName("业务专属受管异步线程池与舱壁隔离端到端集成测试")
class CoreReportExportScenarioServiceTest {

    @Autowired
    private CoreReportExportScenarioService exportScenarioService;

    @Autowired
    @Qualifier("exportTaskExecutor")
    private Executor exportTaskExecutor;

    @Autowired
    @Qualifier("export")
    private Executor exportAliasExecutor;

    @Autowired
    private Executor defaultTaskExecutor;

    @AfterEach
    void tearDown() {
        MDC.clear();
        UserContextHolder.clear();
        TenantContextHolder.clear();
    }

    @Test
    @DisplayName("测试专有 export 业务线程池装配、别名注入与核心参数")
    void testExportPoolConfigurationAndAliases() {
        // 验证主池与别名一致性
        assertThat(exportTaskExecutor).isNotNull();
        assertThat(exportAliasExecutor).isSameAs(exportTaskExecutor);
        assertThat(exportTaskExecutor).isInstanceOf(ThreadPoolTaskExecutor.class);

        ThreadPoolTaskExecutor executor = (ThreadPoolTaskExecutor) exportTaskExecutor;
        assertThat(executor.getCorePoolSize()).isEqualTo(2);
        assertThat(executor.getMaxPoolSize()).isEqualTo(4);
        assertThat(executor.getQueueCapacity()).isEqualTo(50);
        assertThat(executor.getThreadNamePrefix()).isEqualTo("sample-export-async-");

        // 验证默认 Executor 注入（@Primary）为主池且与专有子池隔离
        assertThat(defaultTaskExecutor).isInstanceOf(ThreadPoolTaskExecutor.class);
        ThreadPoolTaskExecutor defaultPool = (ThreadPoolTaskExecutor) defaultTaskExecutor;
        assertThat(defaultPool.getThreadNamePrefix()).isEqualTo("sample-core-async-");
        assertThat(defaultPool).isNotSameAs(executor);
    }

    @Test
    @DisplayName("测试 @Async('export') 报表导出异步调度与四维上下文无损穿透")
    void testExportReportAsyncWithContextPropagation() throws Exception {
        // 1. 在主线程注入四维上下文
        MDC.put("traceId", "trace-export-9999");
        UserContextHolder.setUserId("user-reporter-888");
        TenantContextHolder.setTenantId("tenant-finance-01");
        TenantContextHolder.setIgnoreTenant(false);

        // 2. 调度 @Async("export") 业务异步方法
        CompletableFuture<Map<String, String>> future = exportScenarioService.exportReportAsync("月度财务报表.xlsx");
        Map<String, String> result = future.get(5, TimeUnit.SECONDS);

        // 3. 验证执行结果
        assertThat(result)
                .containsEntry("reportName", "月度财务报表.xlsx")
                .containsEntry("traceId", "trace-export-9999")
                .containsEntry("userId", "user-reporter-888")
                .containsEntry("tenantId", "tenant-finance-01")
                .containsEntry("ignoreTenant", "false");

        // 4. 验证执行线程归属于专属子池 sample-export-async- 前缀
        assertThat(result.get("threadName")).startsWith("sample-export-async-");
    }
}
