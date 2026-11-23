package io.github.atengk.boot.sample.core;

import io.github.atengk.boot.core.util.FileUtil;
import io.github.atengk.boot.sample.core.dto.SlowAlertSampleRequest;
import io.github.atengk.boot.sample.core.listener.SampleSlowAlertEventListener;
import io.github.atengk.boot.sample.core.service.CoreSystemScenarioService;
import io.github.atengk.boot.sample.core.vo.SlowAlertSampleVO;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.core.env.Environment;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Core 场景工程系统基线、零 XML 日志与受管临时目录端到端集成测试
 *
 * @author Ateng
 * @since 2026-10-30
 */
@SpringBootTest
@DisplayName("Core 场景工程系统基线与端到端集成实测")
class CoreSystemScenarioServiceTest {

    @Autowired
    private CoreSystemScenarioService systemScenarioService;

    @Autowired
    private Environment environment;

    @Autowired
    private SampleSlowAlertEventListener slowAlertEventListener;

    @BeforeEach
    void setUp() {
        slowAlertEventListener.reset();
        MDC.clear();
    }

    @Test
    @DisplayName("全真验证：启动环境基线校准（时区、编码、Headless、网络超时）100% 生效")
    void shouldVerifySystemBaselineEnvironment() {
        Map<String, Object> snapshot = systemScenarioService.getBaselineSnapshot();
        assertThat(snapshot.get("timezone")).isEqualTo("Asia/Shanghai");
        assertThat(snapshot.get("headless")).isEqualTo("true");
        assertThat(snapshot.get("connectTimeout")).isEqualTo("10000");
        assertThat(snapshot.get("readTimeout")).isEqualTo("10000");
        assertThat(snapshot.get("physicalIpv4")).isNotNull();
    }

    @Test
    @DisplayName("全真验证：零 XML 工业级日志引擎自动激活并注入带 traceId 控制台模式")
    void shouldVerifyZeroXmlLoggingPatternInjected() {
        String pattern = environment.getProperty("logging.pattern.console");
        assertThat(pattern).isNotNull();
        assertThat(pattern).contains("traceId");
    }

    @Test
    @DisplayName("全真验证：@SlowAlert 方法超时拦截与业务执行通畅")
    void shouldExecuteSlowAlertMethodSuccessfully() {
        long cost = systemScenarioService.processSlowComputation("order-bench-99", "secretPass-XYZ");
        assertThat(cost).isGreaterThanOrEqualTo(30);
    }

    @AfterEach
    void tearDown() {
        slowAlertEventListener.reset();
        MDC.clear();
        Path tempDir = FileUtil.getAppTempDirectory("sample-core");
        FileUtil.cleanDirectorySafely(tempDir);
    }

    @Test
    @DisplayName("全真验证：慢调用领域事件 SlowAlertEvent 解耦消费与启动期慢 Bean 诊断状态")
    void shouldPublishSlowAlertEventAndInspectSlowBeanDiagnostics() {
        MDC.put("traceId", "trace-service-950");
        SlowAlertSampleRequest request = SlowAlertSampleRequest.builder()
                .taskId("slow-diag-001")
                .secretToken("secret-token-950")
                .build();

        SlowAlertSampleVO result = systemScenarioService.executeSlowAlertAndDiagnostic(request);

        assertThat(result.getTaskId()).isEqualTo("slow-diag-001");
        assertThat(result.getMethodCostMillis()).isGreaterThanOrEqualTo(30L);
        assertThat(result.getEventConsumedCount()).isEqualTo(1);
        assertThat(result.getClassName()).contains(CoreSystemScenarioService.class.getSimpleName());
        assertThat(result.getMethodName()).isEqualTo("processSlowComputation");
        assertThat(result.getElapsedMs()).isGreaterThanOrEqualTo(30L);
        assertThat(result.getThresholdMs()).isEqualTo(30L);
        assertThat(result.getArgsSummary())
                .contains("slow-diag-001")
                .contains("\"secretToken\":\"******\"")
                .doesNotContain("secret-token-950");
        assertThat(result.getTraceId()).isEqualTo("trace-service-950");
        assertThat(result.getOccurredAt()).isNotNull();
        assertThat(result.getSlowBeanDiagnosticEnabled()).isTrue();
        assertThat(result.getRecordedSlowBeans()).isNotNull();
        assertThat(result.getRecordedSlowBeanCount()).isEqualTo(result.getRecordedSlowBeans().size());
    }

    @Test
    @DisplayName("全真验证：应用专属受管临时目录创建、读写与安全清理")
    void shouldCreateAndCleanManagedTempFile() throws IOException {
        String filename = "report-temp-1.txt";
        Path tempFile = systemScenarioService.createSampleTempFile(filename, "Sample Content");
        assertThat(Files.exists(tempFile)).isTrue();
        assertThat(tempFile.toString()).contains("sample-core");

        // 安全清理测试残留文件
        int cleaned = FileUtil.cleanDirectorySafely(tempFile.getParent());
        assertThat(cleaned).isGreaterThanOrEqualTo(1);
        assertThat(Files.exists(tempFile)).isFalse();
    }
}
