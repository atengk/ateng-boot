package io.github.atengk.boot.sample.ssh.interceptor;

import io.github.atengk.boot.ssh.interceptor.SshCommandInterceptor;
import io.github.atengk.boot.ssh.model.SshExecOptions;
import io.github.atengk.boot.ssh.model.SshExecResult;
import io.github.atengk.boot.ssh.model.SshServerInfo;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import java.util.concurrent.atomic.AtomicLong;

/**
 * 示例自定义 SSH 命令审计拦截器（演示 SshCommandInterceptor SPI 与 @Order 扩展装配）
 *
 * @author Ateng
 * @since 2026-11-17
 */
@Slf4j
@Component
@Order(Ordered.LOWEST_PRECEDENCE)
public class SampleCommandAuditInterceptor implements SshCommandInterceptor {

    private final AtomicLong executedCount = new AtomicLong(0);
    private final AtomicLong errorCount = new AtomicLong(0);

    @Override
    public void beforeExecute(SshServerInfo serverInfo, String command, SshExecOptions options) {
        String target = serverInfo != null ? serverInfo.toSessionKey() : "unknown";
        log.debug("SampleCommandAuditInterceptor: 准备执行远程命令 [target={}, command={}]", target, command);
    }

    @Override
    public void afterExecute(SshServerInfo serverInfo, String command, SshExecOptions options, SshExecResult result) {
        executedCount.incrementAndGet();
        String target = serverInfo != null ? serverInfo.toSessionKey() : "unknown";
        log.info("SampleCommandAuditInterceptor: 命令执行完成审计 [target={}, exitCode={}, truncated={}]",
                target, result.getExitCode(), result.isTruncated());
    }

    @Override
    public void onError(SshServerInfo serverInfo, String command, SshExecOptions options, Throwable error) {
        errorCount.incrementAndGet();
        String target = serverInfo != null ? serverInfo.toSessionKey() : "unknown";
        log.warn("SampleCommandAuditInterceptor: 命令执行异常或被拦截审计 [target={}, error={}]",
                target, error.getMessage());
    }

    /**
     * 获取已审计的成功执行命令累计次数
     *
     * @return 成功执行累计次数
     */
    public long getExecutedCount() {
        return executedCount.get();
    }

    /**
     * 获取已审计的异常/阻断命令累计次数
     *
     * @return 异常或被拦截累计次数
     */
    public long getErrorCount() {
        return errorCount.get();
    }
}
