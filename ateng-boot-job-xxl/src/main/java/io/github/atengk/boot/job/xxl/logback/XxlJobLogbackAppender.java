package io.github.atengk.boot.job.xxl.logback;

import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.classic.spi.IThrowableProxy;
import ch.qos.logback.classic.spi.StackTraceElementProxy;
import ch.qos.logback.core.AppenderBase;
import com.xxl.job.core.context.XxlJobContext;
import com.xxl.job.core.log.XxlJobFileAppender;
import org.springframework.util.StringUtils;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.Map;

/**
 * XXL-Job 专属 Logback 日志动态镜像追加器
 *
 * <p>无感截获应用中通过 SLF4J 打印的标准日志，仅当当前执行线程处于 {@link XxlJobContext} 调度有效生命周期时，
 * 将日志格式化后自动镜像同步写入当前调度的本地日志分片文件，使得 XXL-Job 调度中心控制台能够无缝呈现业务全量执行日志与异常堆栈。
 * 当处于常规 Web 或离线业务线程时，前置探活快速跳过，保证零开销与零侵入。</p>
 *
 * @author Ateng
 * @since 2026-10-31
 */
public class XxlJobLogbackAppender extends AppenderBase<ILoggingEvent> {

    private static final DateTimeFormatter DATE_FORMATTER = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss.SSS");

    @Override
    protected void append(ILoggingEvent event) {
        // 1. 若当前线程未处于 XXL-Job 调度执行期，直接快速旁路返回，实现零开销
        XxlJobContext xxlJobContext = XxlJobContext.getXxlJobContext();
        if (xxlJobContext == null) {
            return;
        }

        String logFileName = xxlJobContext.getLogFileName();
        if (!StringUtils.hasText(logFileName)) {
            return;
        }

        try {
            // 2. 格式化日志输出内容
            StringBuilder sb = new StringBuilder();
            LocalDateTime time = LocalDateTime.ofInstant(Instant.ofEpochMilli(event.getTimeStamp()), ZoneId.systemDefault());
            sb.append(DATE_FORMATTER.format(time)).append(" ");
            sb.append("[").append(event.getLevel()).append("] ");
            sb.append("[").append(event.getThreadName()).append("] ");

            Map<String, String> mdcPropertyMap = event.getMDCPropertyMap();
            String traceId = (mdcPropertyMap != null) ? mdcPropertyMap.get("traceId") : null;
            if (StringUtils.hasText(traceId)) {
                sb.append("[").append(traceId).append("] ");
            }

            sb.append("[").append(event.getLoggerName()).append("] - ");
            sb.append(event.getFormattedMessage());

            // 3. 处理异常堆栈详情
            IThrowableProxy throwableProxy = event.getThrowableProxy();
            if (throwableProxy != null) {
                sb.append("\n");
                appendThrowable(sb, throwableProxy);
            }

            // 4. 镜像写入当前分片日志文件
            XxlJobFileAppender.appendLog(logFileName, sb.toString());
        } catch (Throwable t) {
            addError("Failed to mirror log to XXL-Job log file: " + logFileName, t);
        }
    }

    private void appendThrowable(StringBuilder sb, IThrowableProxy tp) {
        sb.append(tp.getClassName()).append(": ").append(tp.getMessage()).append("\n");
        StackTraceElementProxy[] stepArray = tp.getStackTraceElementProxyArray();
        if (stepArray != null) {
            for (StackTraceElementProxy step : stepArray) {
                sb.append("\tat ").append(step.getStackTraceElement().toString()).append("\n");
            }
        }
        if (tp.getSuppressed() != null) {
            for (IThrowableProxy s : tp.getSuppressed()) {
                sb.append("Suppressed: ");
                appendThrowable(sb, s);
            }
        }
        if (tp.getCause() != null) {
            sb.append("Caused by: ");
            appendThrowable(sb, tp.getCause());
        }
    }

}
