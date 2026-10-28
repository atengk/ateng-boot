package io.github.atengk.boot.job.local.exception;

import io.github.atengk.boot.core.exception.BusinessException;
import lombok.Getter;

import java.time.Duration;

/**
 * 任务执行硬超时熔断异常
 * <p>
 * 当任务单次执行耗时超过配置的 {@code timeout} 阈值时抛出，触发线程中断并记录 TIMEOUT 终态。
 * </p>
 *
 * @author Ateng
 * @since 2026-10-25
 */
@Getter
public class JobTimeoutException extends BusinessException {

    private static final long serialVersionUID = 1L;

    /**
     * 发生超时的任务唯一名称
     */
    private final String jobName;

    /**
     * 超时阈值时长
     */
    private final Duration timeout;

    public JobTimeoutException(String jobName, Duration timeout) {
        this("Job [" + jobName + "] execution timed out after " + timeout, jobName, timeout, null);
    }

    public JobTimeoutException(String message, String jobName, Duration timeout) {
        this(message, jobName, timeout, null);
    }

    public JobTimeoutException(String message, String jobName, Duration timeout, Throwable cause) {
        super(message, cause);
        this.jobName = jobName;
        this.timeout = timeout;
    }
}
