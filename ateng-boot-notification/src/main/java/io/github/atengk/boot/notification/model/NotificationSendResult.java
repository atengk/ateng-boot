package io.github.atengk.boot.notification.model;

import lombok.Builder;
import lombok.Getter;
import lombok.ToString;

import java.io.Serializable;
import java.time.Duration;

/**
 * 协同通知发信执行结果领域载荷
 * 结构化记录全局消息 ID、目标渠道、目标平台、发信状态、耗时及平台原始响应
 *
 * @author Ateng
 * @since 2026-10-23
 */
@Getter
@Builder
@ToString
public class NotificationSendResult implements Serializable {

    private static final long serialVersionUID = 1L;

    /**
     * 本次发信唯一业务追踪 ID
     */
    private final String messageId;

    /**
     * 发送渠道标识
     */
    private final String channel;

    /**
     * 目标协同平台类型
     */
    private final Platform platform;

    /**
     * 发送是否成功
     */
    private final boolean success;

    /**
     * 网络通信与发信总耗时
     */
    private final Duration costDuration;

    /**
     * 协同平台返回的原始响应报文
     */
    private final String rawResponse;

    /**
     * 失败原因或错误描述（成功时为 null）
     */
    private final String errorMessage;

    /**
     * 构建发信成功结果
     *
     * @param messageId    消息唯一 ID
     * @param channel      渠道名称
     * @param platform     平台类型
     * @param costDuration 网络通信耗时
     * @param rawResponse  平台原始响应
     * @return 发信成功结果载荷
     */
    public static NotificationSendResult success(String messageId, String channel, Platform platform,
                                                 Duration costDuration, String rawResponse) {
        return NotificationSendResult.builder()
                .messageId(messageId)
                .channel(channel)
                .platform(platform)
                .success(true)
                .costDuration(costDuration)
                .rawResponse(rawResponse)
                .errorMessage(null)
                .build();
    }

    /**
     * 构建发信失败结果
     *
     * @param messageId    消息唯一 ID
     * @param channel      渠道名称
     * @param platform     平台类型
     * @param costDuration 网络通信耗时
     * @param rawResponse  平台原始响应
     * @param errorMessage 错误描述
     * @return 发信失败结果载荷
     */
    public static NotificationSendResult failure(String messageId, String channel, Platform platform,
                                                 Duration costDuration, String rawResponse, String errorMessage) {
        return NotificationSendResult.builder()
                .messageId(messageId)
                .channel(channel)
                .platform(platform)
                .success(false)
                .costDuration(costDuration)
                .rawResponse(rawResponse)
                .errorMessage(errorMessage)
                .build();
    }
}
