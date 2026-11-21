package io.github.atengk.boot.sms.model;

import lombok.Builder;
import lombok.Getter;

import java.io.Serializable;
import java.util.Collections;
import java.util.List;
import java.util.Map;

/**
 * 主动查询短信发送状态与送达回执结果值对象
 *
 * @author Ateng
 * @since 2026-11-21
 */
@Getter
@Builder
public class SmsDeliveryQueryResult implements Serializable {

    private static final long serialVersionUID = 1L;

    /**
     * 当前短信厂商驱动是否支持主动发送状态查询（false 表示不支持而非错误）
     */
    private final boolean supported;

    /**
     * 渠道名称
     */
    private final String channelName;

    /**
     * 云厂商枚举标识
     */
    private final SmsSupplier supplier;

    /**
     * 目标手机号
     */
    private final String phone;

    /**
     * 业务回执流水号
     */
    private final String bizId;

    /**
     * 归一化后的送达状态
     */
    private final SmsDeliveryStatus deliveryStatus;

    /**
     * 云厂商原生状态码或错误码
     */
    private final String rawStatusCode;

    /**
     * 云厂商原生状态描述或补充说明
     */
    private final String rawStatusDesc;

    /**
     * 短信实际发送时间文本
     */
    private final String sendTime;

    /**
     * 终端用户收到时间文本
     */
    private final String receiveTime;

    /**
     * 语义化说明信息
     */
    private final String message;

    /**
     * 原始查询明细记录快照列表
     */
    @Builder.Default
    private final List<Map<String, Object>> details = Collections.emptyList();

    /**
     * 构造不支持主动查询状态时的明确契约响应对象
     *
     * @param channelName 渠道名称
     * @param supplier    云厂商标识
     * @param reason      不支持说明
     * @return SmsDeliveryQueryResult (supported=false)
     */
    public static SmsDeliveryQueryResult unsupported(String channelName, SmsSupplier supplier, String reason) {
        return SmsDeliveryQueryResult.builder()
                .supported(false)
                .channelName(channelName)
                .supplier(supplier)
                .deliveryStatus(SmsDeliveryStatus.UNKNOWN)
                .message(reason)
                .details(Collections.emptyList())
                .build();
    }
}
