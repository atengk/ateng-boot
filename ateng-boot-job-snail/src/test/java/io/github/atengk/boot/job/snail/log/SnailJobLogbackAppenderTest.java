package io.github.atengk.boot.job.snail.log;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.LoggerContext;
import ch.qos.logback.classic.spi.ILoggingEvent;
import com.aizuda.snailjob.client.common.log.report.LogMeta;
import com.aizuda.snailjob.client.common.log.support.SnailJobLogManager;
import com.aizuda.snailjob.client.job.core.executor.JobContextManager;
import com.aizuda.snailjob.common.core.model.JobContext;
import com.aizuda.snailjob.common.log.constant.LogFieldConstants;
import com.aizuda.snailjob.common.log.enums.LogTypeEnum;
import io.github.atengk.boot.job.snail.context.SnailJobContext;
import io.github.atengk.boot.job.snail.context.SnailJobContextHolder;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * SnailJob Logback 日志动态镜像单测
 *
 * <p>100% 离线脱网自洽，全面验证：
 * <ul>
 *     <li>脱离 SnailJob 上下文时 Appender 静默休眠零开销；</li>
 *     <li>任务与重试生命周期内 SLF4J 标准日志透明镜像；</li>
 *     <li>已打 remote 标记的日志智能防重；</li>
 *     <li>Logback 追加器注册与销毁生命周期的确定性释放。</li>
 * </ul></p>
 *
 * @author Ateng
 * @since 2026-10-31
 */
class SnailJobLogbackAppenderTest {

    private static final org.slf4j.Logger log = LoggerFactory.getLogger(SnailJobLogbackAppenderTest.class);

    private SnailJobLogbackAppenderRegistrar registrar;
    private final List<ILoggingEvent> mirroredEvents = new ArrayList<>();

    @BeforeEach
    void setUp() {
        SnailJobLogbackAppender.detachFromRootLogger();
        mirroredEvents.clear();
        SnailJobLogbackAppender.setMirrorConsumer(mirroredEvents::add);

        // 注册追加器到根日志记录器
        registrar = new SnailJobLogbackAppenderRegistrar();
        registrar.afterSingletonsInstantiated();
    }

    @AfterEach
    void tearDown() {
        if (registrar != null) {
            registrar.destroy();
        }
        SnailJobLogbackAppender.detachFromRootLogger();
        SnailJobLogbackAppender.setMirrorConsumer(null);
        SnailJobContextHolder.remove();
        JobContextManager.removeJobContext();
        SnailJobLogManager.removeAll();
        MDC.clear();
    }

    @Test
    @DisplayName("测试非 SnailJob 调度或重试线程：Appender 处于静默休眠状态，零镜像捕获")
    void testAppenderDormantOutsideContext() {
        // 保证无任何 SnailJob 上下文
        assertThat(SnailJobLogbackAppender.isTaskActive()).isFalse();

        log.info("Outside normal info log");
        log.warn("Outside normal warn log");
        log.error("Outside normal error log");

        assertThat(mirroredEvents).isEmpty();
    }

    @Test
    @DisplayName("测试在 SnailJobContextHolder 生命周期内：自动将 SLF4J 日志镜像捕获")
    void testAppenderMirrorsLogsInsideSnailJobContext() {
        SnailJobContext context = new SnailJobContext();
        context.setJobId(1001L);
        context.setTaskBatchId(2001L);
        context.setTaskId(3001L);
        SnailJobContextHolder.set(context);

        assertThat(SnailJobLogbackAppender.isTaskActive()).isTrue();

        log.info("Hello SnailJob mirroring info");
        log.warn("Warning during job execution: threshold exceeded");
        log.error("Business failure occurred", new IllegalStateException("Simulated business error"));

        assertThat(mirroredEvents).hasSize(3);

        ILoggingEvent infoEvent = mirroredEvents.get(0);
        assertThat(infoEvent.getLevel()).isEqualTo(Level.INFO);
        assertThat(infoEvent.getFormattedMessage()).isEqualTo("Hello SnailJob mirroring info");

        ILoggingEvent warnEvent = mirroredEvents.get(1);
        assertThat(warnEvent.getLevel()).isEqualTo(Level.WARN);
        assertThat(warnEvent.getFormattedMessage()).contains("threshold exceeded");

        ILoggingEvent errorEvent = mirroredEvents.get(2);
        assertThat(errorEvent.getLevel()).isEqualTo(Level.ERROR);
        assertThat(errorEvent.getFormattedMessage()).contains("Business failure occurred");
        assertThat(errorEvent.getThrowableProxy()).isNotNull();
        assertThat(errorEvent.getThrowableProxy().getMessage()).isEqualTo("Simulated business error");
    }

    @Test
    @DisplayName("测试在 JobContextManager 生命周期内：自动激活日志镜像")
    void testAppenderMirrorsLogsInsideJobContextManager() {
        JobContext jobContext = new JobContext();
        JobContextManager.setJobContext(jobContext);

        assertThat(SnailJobLogbackAppender.isTaskActive()).isTrue();

        log.info("Log inside JobContextManager");
        assertThat(mirroredEvents).hasSize(1);
        assertThat(mirroredEvents.get(0).getFormattedMessage()).isEqualTo("Log inside JobContextManager");
    }

    @Test
    @DisplayName("测试在 SnailJobLogManager.LogMeta 生命周期内：自动激活日志镜像")
    void testAppenderMirrorsLogsInsideSnailJobLogManager() {
        LogMeta logMeta = new LogMeta();
        SnailJobLogManager.initLogInfo(logMeta, LogTypeEnum.JOB);

        assertThat(SnailJobLogbackAppender.isTaskActive()).isTrue();

        log.info("Log inside SnailJobLogManager");
        assertThat(mirroredEvents).hasSize(1);
        assertThat(mirroredEvents.get(0).getFormattedMessage()).isEqualTo("Log inside SnailJobLogManager");
    }

    @Test
    @DisplayName("测试已打 MDC remote 标记的日志：前置跳过防二次重复镜像")
    void testAppenderIgnoresAlreadyRemoteLogs() {
        SnailJobContext context = new SnailJobContext();
        context.setJobId(1001L);
        SnailJobContextHolder.set(context);

        // 模拟上游已经通过 SnailJobLog.REMOTE 打了 remote 标记
        MDC.put(LogFieldConstants.MDC_REMOTE, "true");

        log.info("This is an explicit remote log, should not be mirrored twice");

        assertThat(mirroredEvents).isEmpty();
    }

    @Test
    @DisplayName("测试 SnailJobLogbackAppenderRegistrar 生命周期：动态挂载与安全卸载")
    void testRegistrarLifecycle() {
        LoggerContext context = (LoggerContext) LoggerFactory.getILoggerFactory();
        Logger rootLogger = context.getLogger(Logger.ROOT_LOGGER_NAME);

        // 已在 setUp 注册
        assertThat(rootLogger.getAppender(SnailJobLogbackAppenderRegistrar.APPENDER_NAME)).isNotNull();

        // 销毁并卸载
        registrar.destroy();
        assertThat(rootLogger.getAppender(SnailJobLogbackAppenderRegistrar.APPENDER_NAME)).isNull();
    }

    @Test
    @DisplayName("测试 static attachToRootLogger 与 detachFromRootLogger 方法")
    void testStaticAttachAndDetach() {
        // 先销毁 setUp 中的实例
        registrar.destroy();

        LoggerContext context = (LoggerContext) LoggerFactory.getILoggerFactory();
        Logger rootLogger = context.getLogger(Logger.ROOT_LOGGER_NAME);
        assertThat(rootLogger.getAppender(SnailJobLogbackAppender.APPENDER_NAME)).isNull();

        // 静态挂载
        SnailJobLogbackAppender.attachToRootLogger();
        assertThat(rootLogger.getAppender(SnailJobLogbackAppender.APPENDER_NAME)).isNotNull();

        // 重复挂载保持幂等
        SnailJobLogbackAppender.attachToRootLogger();
        assertThat(rootLogger.getAppender(SnailJobLogbackAppender.APPENDER_NAME)).isNotNull();

        // 静态卸载
        SnailJobLogbackAppender.detachFromRootLogger();
        assertThat(rootLogger.getAppender(SnailJobLogbackAppender.APPENDER_NAME)).isNull();
    }
}
