package io.github.atengk.boot.sms.event;

import io.github.atengk.boot.sms.model.SmsSupplier;
import lombok.Getter;
import org.springframework.context.ApplicationEvent;

import java.time.Instant;

/**
 * 用户上行回复短信领域事件 (Mobile Originated Message Event)
 * 当用户回复退订指令（如 TD、N）或业务内容时广播
 *
 * @author Ateng
 * @since 2026-11-02
 */
@Getter
public class SmsMoMessageEvent extends ApplicationEvent {

    private static final long serialVersionUID = 1L;

    /**
     * 接收上行回复的短信渠道名称
     */
    private final String channel;

    /**
     * 云厂商供应商类型
     */
    private final SmsSupplier supplier;

    /**
     * 发送回复短信的用户手机号（真明文）
     */
    private final String phone;

    /**
     * 上行回复短信文本内容（如 "TD"）
     */
    private final String content;

    /**
     * 绑定的短信签名
     */
    private final String signName;

    /**
     * 接收时间戳
     */
    private final Instant receiveTime;

    /**
     * 原始回调报文片段
     */
    private final String rawPayload;

    public SmsMoMessageEvent(Object source,
                             String channel,
                             SmsSupplier supplier,
                             String phone,
                             String content,
                             String signName,
                             Instant receiveTime,
                             String rawPayload) {
        super(source);
        this.channel = channel;
        this.supplier = supplier;
        this.phone = phone;
        this.content = content;
        this.signName = signName;
        this.receiveTime = receiveTime != null ? receiveTime : Instant.now();
        this.rawPayload = rawPayload;
    }
}
