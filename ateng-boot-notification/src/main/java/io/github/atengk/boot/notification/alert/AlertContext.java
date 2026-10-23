package io.github.atengk.boot.notification.alert;

import lombok.Builder;
import lombok.Getter;
import lombok.ToString;

import java.io.Serializable;
import java.time.Duration;
import java.time.LocalDateTime;

/**
 * 协同通知异常告警上下文载荷实体
 * 封装异常指纹、调用栈、MDC TraceId、操作人身份与脱敏入参快照
 *
 * @author Ateng
 * @since 2026-10-23
 */
@Getter
@Builder
@ToString
public class AlertContext implements Serializable {

    private static final long serialVersionUID = 1L;

    /**
     * 异常指纹哈希（基于 MD5 无碰撞算法）
     */
    private final String fingerprint;

    /**
     * 目标通知渠道标识
     */
    private final String channel;

    /**
     * 告警卡片标题
     */
    private final String title;

    /**
     * 抛出异常的方法签名（类全路径#方法名）
     */
    private final String methodSignature;

    /**
     * 业务代码首发错误行号
     */
    private final int lineNumber;

    /**
     * 外层抛出的异常类型类名
     */
    private final String exceptionClassName;

    /**
     * 外层异常错误详情
     */
    private final String exceptionMessage;

    /**
     * 根因异常类名
     */
    private final String rootCauseClassName;

    /**
     * 根因异常错误详情
     */
    private final String rootCauseMessage;

    /**
     * MDC 链路追踪标识
     */
    private final String traceId;

    /**
     * 当前操作人用户标识
     */
    private final String userId;

    /**
     * 经过脱敏的安全入参字符串
     */
    private final String desensitizedArgs;

    /**
     * 前 10 行精简核心调用栈文本
     */
    private final String topStackFrames;

    /**
     * 本次告警定制的抑制窗口时长
     */
    private final Duration suppressWindow;

    /**
     * 异常发生时间
     */
    private final LocalDateTime occurrenceTime;
}
