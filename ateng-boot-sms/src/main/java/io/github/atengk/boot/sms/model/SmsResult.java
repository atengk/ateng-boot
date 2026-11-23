package io.github.atengk.boot.sms.model;

import lombok.Builder;
import lombok.Getter;
import lombok.ToString;

import java.io.Serializable;

/**
 * 统一短信发信结果领域对象
 *
 * @author Ateng
 * @since 2026-11-02
 */
@Getter
@Builder
@ToString
public class SmsResult implements Serializable {

    private static final long serialVersionUID = 1L;

    /**
     * 是否受理/发送成功
     */
    private final boolean success;

    /**
     * 实际投递的短信渠道名称
     */
    private final String channel;

    /**
     * 云厂商供应商类型
     */
    private final SmsSupplier supplier;

    /**
     * 云厂商业务流水号（用于关联运营商异步送达回执）
     */
    private final String bizId;

    /**
     * 云厂商请求唯一跟踪 ID
     */
    private final String requestId;

    /**
     * 云厂商原始响应状态码
     */
    private final String rawCode;

    /**
     * 云厂商原始状态描述
     */
    private final String rawMessage;

    /**
     * 预估计费条数（长短信分片计费）
     */
    private final Integer fee;

    /**
     * 投递状态
     */
    private final SmsDeliveryStatus deliveryStatus;

    /**
     * 成功响应快捷构建
     *
     * @param channel   渠道名称
     * @param supplier  服务商
     * @param bizId     业务流水号
     * @param requestId 请求ID
     * @param fee       计费条数
     * @return SmsResult 实例
     */
    public static SmsResult success(String channel, SmsSupplier supplier, String bizId, String requestId, Integer fee) {
        return SmsResult.builder()
                .success(true)
                .channel(channel)
                .supplier(supplier)
                .bizId(bizId)
                .requestId(requestId)
                .rawCode("OK")
                .rawMessage("Send Success")
                .fee(fee != null ? fee : 1)
                .deliveryStatus(SmsDeliveryStatus.ACCEPTED)
                .build();
    }

    /**
     * 本地沙箱模拟成功快捷构建
     *
     * @param channel   渠道名称
     * @param bizId     模拟流水号
     * @param requestId 模拟请求ID
     * @return SmsResult 实例
     */
    public static SmsResult mock(String channel, String bizId, String requestId) {
        return SmsResult.builder()
                .success(true)
                .channel(channel)
                .supplier(SmsSupplier.MOCK)
                .bizId(bizId)
                .requestId(requestId)
                .rawCode("OK")
                .rawMessage("Mock Send Success")
                .fee(1)
                .deliveryStatus(SmsDeliveryStatus.MOCKED)
                .build();
    }

    /**
     * 失败响应快捷构建
     *
     * @param channel    渠道名称
     * @param supplier   服务商
     * @param rawCode    厂商错误码
     * @param rawMessage 厂商错误描述
     * @return SmsResult 实例
     */
    public static SmsResult failure(String channel, SmsSupplier supplier, String rawCode, String rawMessage) {
        return SmsResult.builder()
                .success(false)
                .channel(channel)
                .supplier(supplier)
                .rawCode(rawCode)
                .rawMessage(rawMessage)
                .fee(0)
                .deliveryStatus(SmsDeliveryStatus.FAILED)
                .build();
    }

    /**
     * 前置拦截响应快捷构建（黑名单或预算超限）
     *
     * @param channel 渠道名称
     * @param reason  拦截原因
     * @return SmsResult 实例
     */
    public static SmsResult blocked(String channel, String reason) {
        return SmsResult.builder()
                .success(false)
                .channel(channel)
                .rawCode("BLOCKED")
                .rawMessage(reason)
                .fee(0)
                .deliveryStatus(SmsDeliveryStatus.BLOCKED)
                .build();
    }
}
