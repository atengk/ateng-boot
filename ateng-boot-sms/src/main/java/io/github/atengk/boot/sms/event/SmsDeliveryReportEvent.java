package io.github.atengk.boot.sms.event;

import io.github.atengk.boot.sms.model.SmsSupplier;
import lombok.Getter;
import org.springframework.context.ApplicationEvent;

import java.time.Instant;

/**
 * 运营商短信异步送达回执领域事件
 * 当外部云厂商回调接口推送短信送达状态报告时广播
 *
 * @author Ateng
 * @since 2026-11-02
 */
@Getter
public class SmsDeliveryReportEvent extends ApplicationEvent {

    private static final long serialVersionUID = 1L;

    /**
     * 接收回调的短信渠道名称
     */
    private final String channel;

    /**
     * 云厂商供应商类型
     */
    private final SmsSupplier supplier;

    /**
     * 接收短信的目标手机号（真明文）
     */
    private final String phone;

    /**
     * 运营商是否成功送达
     */
    private final boolean success;

    /**
     * 运营商原始状态码（如 DELIVRD）
     */
    private final String resultCode;

    /**
     * 运营商状态描述
     */
    private final String resultMessage;

    /**
     * 云厂商业务流水号
     */
    private final String bizId;

    /**
     * 送达回执时间戳
     */
    private final Instant reportTime;

    /**
     * 原始回调报文片段
     */
    private final String rawPayload;

    public SmsDeliveryReportEvent(Object source,
                                  String channel,
                                  SmsSupplier supplier,
                                  String phone,
                                  boolean success,
                                  String resultCode,
                                  String resultMessage,
                                  String bizId,
                                  Instant reportTime,
                                  String rawPayload) {
        super(source);
        this.channel = channel;
        this.supplier = supplier;
        this.phone = phone;
        this.success = success;
        this.resultCode = resultCode;
        this.resultMessage = resultMessage;
        this.bizId = bizId;
        this.reportTime = reportTime != null ? reportTime : Instant.now();
        this.rawPayload = rawPayload;
    }
}
