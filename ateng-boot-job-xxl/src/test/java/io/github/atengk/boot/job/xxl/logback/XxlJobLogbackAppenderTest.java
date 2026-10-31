package io.github.atengk.boot.job.xxl.logback;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.LoggerContext;
import com.xxl.job.core.context.XxlJobContext;
import com.xxl.job.core.log.XxlJobFileAppender;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Date;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * XXL-Job Logback 日志动态镜像单测
 *
 * @author Ateng
 * @since 2026-10-31
 */
class XxlJobLogbackAppenderTest {

    private static final org.slf4j.Logger log = LoggerFactory.getLogger(XxlJobLogbackAppenderTest.class);

    private XxlJobLogbackAppenderRegistrar registrar;

    @BeforeEach
    void setUp(@TempDir Path tempDir) throws IOException {
        // 初始化日志根目录至独立临时目录，保证隔离
        XxlJobFileAppender.initLogPath(tempDir.toString());

        // 注册 Logback 追加器
        registrar = new XxlJobLogbackAppenderRegistrar();
        registrar.afterSingletonsInstantiated();
    }

    @AfterEach
    void tearDown() {
        if (registrar != null) {
            registrar.destroy();
        }
        XxlJobContext.setXxlJobContext(null);
        MDC.clear();
    }

    @Test
    @DisplayName("测试非 XXL-Job 上下文下：Appender 处于静默休眠状态，零物理文件写入")
    void testAppenderDormantWhenOutsideContext(@TempDir Path tempDir) {
        XxlJobContext.setXxlJobContext(null);

        // 触发普通日志打印
        log.info("Outside normal log message");
        log.warn("Outside warning message");

        // 此时没有任务上下文，临时目录下不应该有日志生成
        assertThat(tempDir.toFile().listFiles()).isEmpty();
    }

    @Test
    @DisplayName("测试在 XXL-Job 上下文生命周期内：自动将 SLF4J 标准日志镜像写入当前任务分片文件")
    void testAppenderMirrorsLogsInsideContext() throws IOException {
        Date now = new Date();
        long logId = 99001L;
        String logFileName = XxlJobFileAppender.makeLogFileName(now, logId);
        XxlJobContext context = new XxlJobContext(100L, "test-param", logId, now.getTime(), logFileName, 0, 1);
        XxlJobContext.setXxlJobContext(context);
        MDC.put("traceId", "trace-job-99001");

        // 触发各级日志打印
        log.info("Hello XXL-Job log mirroring!");
        log.warn("This is a warning during job execution");
        log.error("An error occurred during scheduled task", new IllegalStateException("Simulated business error"));

        // 验证文件已被镜像写入
        Path logFilePath = Path.of(logFileName);
        assertThat(Files.exists(logFilePath)).isTrue();

        String content = Files.readString(logFilePath);
        assertThat(content).contains("Hello XXL-Job log mirroring!");
        assertThat(content).contains("[INFO]");
        assertThat(content).contains("[WARN]");
        assertThat(content).contains("[ERROR]");
        assertThat(content).contains("[trace-job-99001]");
        assertThat(content).contains("Simulated business error");
        assertThat(content).contains("IllegalStateException");
        assertThat(content).contains("at io.github.atengk.boot.job.xxl.logback.XxlJobLogbackAppenderTest");
    }

    @Test
    @DisplayName("测试 LogbackRegistrar 生命周期：动态挂载与安全卸载")
    void testRegistrarLifecycle() {
        LoggerContext context = (LoggerContext) LoggerFactory.getILoggerFactory();
        Logger rootLogger = context.getLogger(Logger.ROOT_LOGGER_NAME);

        // 已在 setUp 注册
        assertThat(rootLogger.getAppender(XxlJobLogbackAppenderRegistrar.APPENDER_NAME)).isNotNull();

        // 销毁并卸载
        registrar.destroy();
        assertThat(rootLogger.getAppender(XxlJobLogbackAppenderRegistrar.APPENDER_NAME)).isNull();
    }

}
