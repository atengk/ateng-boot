package io.github.atengk.boot.sms.provider;

import io.github.atengk.boot.sms.model.SmsChannel;
import io.github.atengk.boot.sms.model.SmsRequest;
import io.github.atengk.boot.sms.model.SmsResult;
import io.github.atengk.boot.sms.model.SmsSupplier;

/**
 * 异构云厂商短信驱动 SPI 接口
 * 负责底层网络通信、协议加签鉴权与厂商响应结果转译
 *
 * @author Ateng
 * @since 2026-11-02
 */
public interface SmsProvider {

    /**
     * 获取当前驱动绑定的短信厂商类型
     *
     * @return SmsSupplier 枚举
     */
    SmsSupplier getSupplier();

    /**
     * 执行单条短信同步发送
     *
     * @param request 发信请求值对象
     * @param channel 目标发信渠道配置
     * @return SmsResult 发送结果
     */
    SmsResult send(SmsRequest request, SmsChannel channel);

    /**
     * 校验当前驱动在目标渠道配置下是否可用
     *
     * @param channel 渠道配置
     * @return 是否可用
     */
    default boolean isAvailable(SmsChannel channel) {
        return channel != null;
    }
}
