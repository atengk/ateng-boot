package io.github.atengk.boot.job.snail.event;

import org.springframework.context.ApplicationEvent;

import java.io.Serializable;

/**
 * SnailJob 分布式重试死信沉降告警事件
 *
 * <p>当分布式重试达到最大重试次数或触发不可恢复异常时发布，携带重试场景、幂等标识、
 * 业务单号、执行器类全限定名、入参载荷、累计重试次数与异常堆栈，直接桥接协同告警与死信治理。</p>
 *
 * @author Ateng
 * @since 2026-10-31
 */
public class SnailRetryDeadLetterEvent extends ApplicationEvent implements Serializable {

    private static final long serialVersionUID = 1L;

    private final String scene;
    private final String idempotentId;
    private final String bizNo;
    private final String executorClassName;
    private final String argsStr;
    private final int retryCount;
    private final Throwable throwable;
    private final String traceId;

    public SnailRetryDeadLetterEvent(
            Object source,
            String scene,
            String idempotentId,
            String bizNo,
            String executorClassName,
            String argsStr,
            int retryCount,
            Throwable throwable,
            String traceId) {
        super(source);
        this.scene = scene;
        this.idempotentId = idempotentId;
        this.bizNo = bizNo;
        this.executorClassName = executorClassName;
        this.argsStr = argsStr;
        this.retryCount = retryCount;
        this.throwable = throwable;
        this.traceId = traceId;
    }

    public String getScene() {
        return scene;
    }

    public String getIdempotentId() {
        return idempotentId;
    }

    public String getBizNo() {
        return bizNo;
    }

    public String getExecutorClassName() {
        return executorClassName;
    }

    public String getArgsStr() {
        return argsStr;
    }

    public int getRetryCount() {
        return retryCount;
    }

    public Throwable getThrowable() {
        return throwable;
    }

    public String getTraceId() {
        return traceId;
    }

    @Override
    public String toString() {
        return "SnailRetryDeadLetterEvent{" +
                "scene='" + scene + '\'' +
                ", idempotentId='" + idempotentId + '\'' +
                ", bizNo='" + bizNo + '\'' +
                ", executorClassName='" + executorClassName + '\'' +
                ", retryCount=" + retryCount +
                ", traceId='" + traceId + '\'' +
                ", throwable=" + (throwable != null ? throwable.getMessage() : "null") +
                '}';
    }
}
