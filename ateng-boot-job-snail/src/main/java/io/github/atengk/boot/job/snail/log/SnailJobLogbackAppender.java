package io.github.atengk.boot.job.snail.log;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.LoggerContext;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.classic.spi.IThrowableProxy;
import ch.qos.logback.classic.spi.LoggerContextVO;
import ch.qos.logback.classic.spi.StackTraceElementProxy;
import ch.qos.logback.core.AppenderBase;
import com.aizuda.snailjob.client.common.LogReport;
import com.aizuda.snailjob.client.common.log.report.LogReportFactory;
import com.aizuda.snailjob.client.common.log.support.SnailJobLogManager;
import com.aizuda.snailjob.client.common.rpc.client.common.ClientInfo;
import com.aizuda.snailjob.client.job.core.executor.JobContextManager;
import com.aizuda.snailjob.common.log.SnailJobLog;
import com.aizuda.snailjob.common.log.constant.LogFieldConstants;
import com.aizuda.snailjob.common.log.dto.LogContentDTO;
import io.github.atengk.boot.job.snail.context.SnailJobContextHolder;
import org.slf4j.ILoggerFactory;
import org.slf4j.LoggerFactory;
import org.slf4j.Marker;
import org.slf4j.event.KeyValuePair;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Consumer;

/**
 * SnailJob 专属 Logback 日志动态镜像追加器
 *
 * <p>无感截获应用中通过标准 SLF4J 打印的业务日志（{@code log.info/warn/error}），
 * 仅当当前执行线程处于 SnailJob 调度任务（Job）或分布式重试（Retry）有效执行生命周期时，
 * 自动将日志镜像上报至 SnailJob 服务端控制台：
 * <ul>
 *     <li><b>执行域智能探测</b>：检测 {@link SnailJobLogManager#getLogMeta()}、{@link SnailJobContextHolder#get()}
 *         或 {@link JobContextManager#getJobContext()}，脱离任务线程时瞬间旁路跳过，零开销；</li>
 *     <li><b>双重防重与防递归风暴</b>：基于 {@link ThreadLocal} 递归标记以及 {@code MDC.get("remote")} 前置检查，
 *         彻底杜绝循环镜像上报与日志风暴；</li>
 *     <li><b>载荷安全截断防线</b>：依据 {@code maxPayloadSize} 对超长单条消息执行按 UTF-8 字节安全截断并追加
 *         {@code ...[truncated]} 尾标，保护 Netty/HTTP 缓冲而不阻断主业务流程；</li>
 *     <li><b>全真测试拦截桩</b>：支持通过 {@link #setMirrorConsumer(Consumer)} 注入测试回调，
 *         保障单测 100% 离线脱网强断言日志镜像行为。</li>
 * </ul></p>
 *
 * @author Ateng
 * @since 2026-10-31
 */
public class SnailJobLogbackAppender extends AppenderBase<ILoggingEvent> {

    public static final String APPENDER_NAME = "SNAIL_JOB_LOGBACK_APPENDER";
    public static final long DEFAULT_MAX_PAYLOAD_BYTES = 1024L * 1024L;
    public static final String TRUNCATED_SUFFIX = "...[truncated]";

    private static final ThreadLocal<Boolean> IN_MIRROR = ThreadLocal.withInitial(() -> Boolean.FALSE);

    private static volatile Consumer<ILoggingEvent> mirrorConsumer;
    private volatile long maxPayloadBytes = DEFAULT_MAX_PAYLOAD_BYTES;

    /**
     * 设置当前追加器实例的日志单条镜像最大字节阈值
     *
     * @param maxBytes 最大允许字节数（{@code <= 0} 时恢复默认 1MB）
     */
    public void setMaxPayloadBytes(long maxBytes) {
        this.maxPayloadBytes = maxBytes > 0 ? maxBytes : DEFAULT_MAX_PAYLOAD_BYTES;
    }

    /**
     * 获取当前追加器实例的日志单条镜像最大字节阈值
     *
     * @return 最大允许字节数
     */
    public long getMaxPayloadBytes() {
        return this.maxPayloadBytes;
    }

    /**
     * 按 UTF-8 字节数安全截断超长文本并附加 {@code ...[truncated]} 标记，严禁劈裂多字节字符或代理对
     *
     * @param message  原始消息文本
     * @param maxBytes 最大字节上限
     * @return 安全截断后的文本
     */
    public static String truncatePayload(String message, long maxBytes) {
        if (message == null || maxBytes <= 0) {
            return message;
        }
        byte[] rawBytes = message.getBytes(StandardCharsets.UTF_8);
        if (rawBytes.length <= maxBytes) {
            return message;
        }
        byte[] suffixBytes = TRUNCATED_SUFFIX.getBytes(StandardCharsets.UTF_8);
        if (maxBytes <= suffixBytes.length) {
            return sliceUtf8ByBytes(TRUNCATED_SUFFIX, (int) maxBytes);
        }
        int allowedContentBytes = (int) (maxBytes - suffixBytes.length);
        return sliceUtf8ByBytes(message, allowedContentBytes) + TRUNCATED_SUFFIX;
    }

    private static String sliceUtf8ByBytes(String text, int maxBytes) {
        if (text == null || maxBytes <= 0) {
            return "";
        }
        StringBuilder sb = new StringBuilder();
        int usedBytes = 0;
        for (int i = 0; i < text.length(); ) {
            int codePoint = text.codePointAt(i);
            int charCount = Character.charCount(codePoint);
            int cpBytes = new String(Character.toChars(codePoint)).getBytes(StandardCharsets.UTF_8).length;
            if (usedBytes + cpBytes > maxBytes) {
                break;
            }
            sb.appendCodePoint(codePoint);
            usedBytes += cpBytes;
            i += charCount;
        }
        return sb.toString();
    }

    /**
     * 设置测试或调试专用的日志镜像消费钩子
     *
     * @param consumer 日志事件消费者
     */
    public static void setMirrorConsumer(Consumer<ILoggingEvent> consumer) {
        mirrorConsumer = consumer;
    }

    /**
     * 判断当前线程是否处于 SnailJob 任务或重试有效执行期
     *
     * @return true 若处于有效调度/重试期
     */
    public static boolean isTaskActive() {
        return SnailJobLogManager.getLogMeta() != null
                || SnailJobContextHolder.get() != null
                || JobContextManager.getJobContext() != null;
    }

    /**
     * 将本追加器动态附加至 Logback Root Logger
     */
    public static void attachToRootLogger() {
        ILoggerFactory loggerFactory = LoggerFactory.getILoggerFactory();
        if (loggerFactory instanceof LoggerContext loggerContext) {
            Logger rootLogger = loggerContext.getLogger(Logger.ROOT_LOGGER_NAME);
            if (rootLogger != null && rootLogger.getAppender(APPENDER_NAME) == null) {
                SnailJobLogbackAppender appender = new SnailJobLogbackAppender();
                appender.setContext(loggerContext);
                appender.setName(APPENDER_NAME);
                appender.start();
                rootLogger.addAppender(appender);
            }
        }
    }

    /**
     * 从 Logback Root Logger 动态卸载本追加器
     */
    public static void detachFromRootLogger() {
        ILoggerFactory loggerFactory = LoggerFactory.getILoggerFactory();
        if (loggerFactory instanceof LoggerContext loggerContext) {
            Logger rootLogger = loggerContext.getLogger(Logger.ROOT_LOGGER_NAME);
            if (rootLogger != null) {
                var appender = rootLogger.getAppender(APPENDER_NAME);
                if (appender != null) {
                    rootLogger.detachAppender(APPENDER_NAME);
                    if (appender.isStarted()) {
                        appender.stop();
                    }
                }
            }
        }
    }

    @Override
    protected void append(ILoggingEvent event) {
        // 1. 若当前线程未处于 SnailJob 调度或重试生命周期内，直接快速旁路返回，实现零开销
        if (!isTaskActive()) {
            return;
        }

        // 2. 防递归安全守卫：若当前正在镜像执行内部，直接跳过
        if (Boolean.TRUE.equals(IN_MIRROR.get())) {
            return;
        }

        // 3. 防重复镜像：若 MDC 中已存在 remote 标记（说明上游已直接调用 SnailJobLog.REMOTE 打印），避免二次上报
        Map<String, String> mdcPropertyMap = event.getMDCPropertyMap();
        if (mdcPropertyMap != null && Objects.nonNull(mdcPropertyMap.get(LogFieldConstants.MDC_REMOTE))) {
            return;
        }

        try {
            IN_MIRROR.set(Boolean.TRUE);

            String rawMessage = event.getFormattedMessage();
            String truncatedMessage = truncatePayload(rawMessage, maxPayloadBytes);
            ILoggingEvent effectiveEvent = Objects.equals(rawMessage, truncatedMessage)
                    ? event
                    : new TruncatedLoggingEvent(event, truncatedMessage);

            // 4. 执行测试桩消费回调（若有）
            Consumer<ILoggingEvent> consumer = mirrorConsumer;
            if (consumer != null) {
                consumer.accept(effectiveEvent);
            }

            // 5. 核心镜像逻辑：若 SnailJob 异步滑动窗口批量上报器可用，直接构建 LogContentDTO 上报
            LogReport logReport = LogReportFactory.get();
            if (logReport != null) {
                LogContentDTO logContentDTO = buildLogContentDTO(effectiveEvent);
                logReport.report(logContentDTO);
            } else {
                // 降级兜底：调用 SnailJobLog.REMOTE 对称镜像打印
                mirrorViaSnailJobLog(effectiveEvent);
            }
        } catch (Throwable t) {
            addError("Failed to mirror SLF4J log to SnailJob server", t);
        } finally {
            IN_MIRROR.remove();
        }
    }

    private LogContentDTO buildLogContentDTO(ILoggingEvent event) {
        LogContentDTO dto = new LogContentDTO();
        dto.addTimeStamp(event.getTimeStamp());
        if (event.getLevel() != null) {
            dto.addLevelField(event.getLevel().levelStr);
        }
        dto.addThreadField(event.getThreadName());
        dto.addMessageField(event.getFormattedMessage());
        dto.addLocationField(getLocationField(event));
        dto.addThrowableField(getThrowableField(event));
        dto.addHostField(ClientInfo.getClientHost());
        dto.addPortField(ClientInfo.getClientPort());
        return dto;
    }

    private String getLocationField(ILoggingEvent event) {
        StackTraceElement[] caller = event.getCallerData();
        if (caller != null && caller.length > 0) {
            return caller[0].toString();
        }
        return null;
    }

    private String getThrowableField(ILoggingEvent event) {
        IThrowableProxy iThrowableProxy = event.getThrowableProxy();
        if (iThrowableProxy != null) {
            StringBuilder sb = new StringBuilder();
            sb.append(iThrowableProxy.getClassName()).append(": ").append(iThrowableProxy.getMessage());
            StackTraceElementProxy[] stepArray = iThrowableProxy.getStackTraceElementProxyArray();
            if (stepArray != null) {
                for (StackTraceElementProxy step : stepArray) {
                    sb.append("\n\tat ").append(step.toString());
                }
            }
            return truncatePayload(sb.toString(), this.maxPayloadBytes);
        }
        return null;
    }

    private void mirrorViaSnailJobLog(ILoggingEvent event) {
        try {
            String msg = event.getFormattedMessage();
            Level level = event.getLevel();
            if (level != null && level.isGreaterOrEqual(Level.ERROR)) {
                SnailJobLog.REMOTE.error(msg);
            } else if (level != null && level.isGreaterOrEqual(Level.WARN)) {
                SnailJobLog.REMOTE.warn(msg);
            } else if (level != null && level.isGreaterOrEqual(Level.INFO)) {
                SnailJobLog.REMOTE.info(msg);
            } else if (level != null && level.isGreaterOrEqual(Level.DEBUG)) {
                SnailJobLog.REMOTE.debug(msg);
            } else {
                SnailJobLog.REMOTE.trace(msg);
            }
        } finally {
            org.slf4j.MDC.remove(LogFieldConstants.MDC_REMOTE);
        }
    }

    /**
     * 超限截断后的日志事件只读委托视图
     */
    private static final class TruncatedLoggingEvent implements ILoggingEvent {

        private final ILoggingEvent delegate;
        private final String truncatedMessage;

        private TruncatedLoggingEvent(ILoggingEvent delegate, String truncatedMessage) {
            this.delegate = delegate;
            this.truncatedMessage = truncatedMessage;
        }

        @Override
        public String getThreadName() {
            return delegate.getThreadName();
        }

        @Override
        public Level getLevel() {
            return delegate.getLevel();
        }

        @Override
        public String getMessage() {
            return truncatedMessage;
        }

        @Override
        public Object[] getArgumentArray() {
            return delegate.getArgumentArray();
        }

        @Override
        public String getFormattedMessage() {
            return truncatedMessage;
        }

        @Override
        public String getLoggerName() {
            return delegate.getLoggerName();
        }

        @Override
        public LoggerContextVO getLoggerContextVO() {
            return delegate.getLoggerContextVO();
        }

        @Override
        public IThrowableProxy getThrowableProxy() {
            return delegate.getThrowableProxy();
        }

        @Override
        public StackTraceElement[] getCallerData() {
            return delegate.getCallerData();
        }

        @Override
        public boolean hasCallerData() {
            return delegate.hasCallerData();
        }

        @Override
        public List<Marker> getMarkerList() {
            return delegate.getMarkerList();
        }

        @Override
        public Map<String, String> getMDCPropertyMap() {
            return delegate.getMDCPropertyMap();
        }

        @Override
        @SuppressWarnings("deprecation")
        public Map<String, String> getMdc() {
            return delegate.getMdc();
        }

        @Override
        public long getTimeStamp() {
            return delegate.getTimeStamp();
        }

        @Override
        public int getNanoseconds() {
            return delegate.getNanoseconds();
        }

        @Override
        public long getSequenceNumber() {
            return delegate.getSequenceNumber();
        }

        @Override
        public List<KeyValuePair> getKeyValuePairs() {
            return delegate.getKeyValuePairs();
        }

        @Override
        public void prepareForDeferredProcessing() {
            delegate.prepareForDeferredProcessing();
        }
    }
}
