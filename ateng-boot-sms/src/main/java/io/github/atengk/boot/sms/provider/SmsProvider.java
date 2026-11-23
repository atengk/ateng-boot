package io.github.atengk.boot.sms.provider;

import io.github.atengk.boot.sms.model.BatchSmsRequest;
import io.github.atengk.boot.sms.model.BatchSmsResult;
import io.github.atengk.boot.sms.model.SmsBalanceResult;
import io.github.atengk.boot.sms.model.SmsChannel;
import io.github.atengk.boot.sms.model.SmsDeliveryQueryResult;
import io.github.atengk.boot.sms.model.SmsRequest;
import io.github.atengk.boot.sms.model.SmsResult;
import io.github.atengk.boot.sms.model.SmsSupplier;

import java.time.LocalDate;

/**
 * 异构云厂商短信驱动 SPI 接口
 * 负责底层网络通信、协议加签鉴权与厂商响应结果转译
 * 支持通过 {@link #getProviderCode()} 注册多个独立自定义厂商驱动实现
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
     * 获取当前驱动的唯一路由编码（不区分大小写，默认回退为 {@link SmsSupplier#getCode()}）
     * 自定义多驱动场景下可覆写此方法返回唯一标识，彻底摆脱单一 CUSTOM 枚举键瓶颈
     *
     * @return 驱动唯一路由编码
     */
    default String getProviderCode() {
        SmsSupplier supplier = getSupplier();
        return supplier != null ? supplier.getCode() : null;
    }

    /**
     * 声明当前驱动是否支持无模板纯文本正文直发
     *
     * @return true 支持无模板纯文本直发，false 仅支持模板发信
     */
    default boolean supportsPlainText() {
        SmsSupplier supplier = getSupplier();
        return supplier != null && supplier.supportsPlainText();
    }

    /**
     * 获取当前驱动单次批量请求的手机号数量上限（默认回退为 {@link SmsSupplier#getBatchLimit()}）
     *
     * @return 单批最大手机号数量
     */
    default int getBatchLimit() {
        SmsSupplier supplier = getSupplier();
        return supplier != null ? supplier.getBatchLimit() : 1000;
    }

    /**
     * 执行单条短信同步发送
     *
     * @param request 发信请求值对象
     * @param channel 目标发信渠道配置
     * @return SmsResult 发送结果
     */
    SmsResult send(SmsRequest request, SmsChannel channel);

    /**
     * 执行原生个性化批量发送（如阿里云 SendBatchSms）
     * 若云厂商不支持原生个性化批量，默认返回 null，由底座 SmsTemplate 自动降级为受管线程池并发执行单条
     *
     * @param request 个性化批量发信请求
     * @param channel 目标发信渠道配置
     * @return BatchSmsResult 批量发送结果，不支持原生批量时返回 null
     */
    default BatchSmsResult sendPersonalizedBatch(BatchSmsRequest request, SmsChannel channel) {
        return null;
    }

    /**
     * 主动查询单条短信发送状态与送达回执详情（不支持主动回查的厂商默认返回 {@code supported=false} 契约结果）
     *
     * @param phone    目标手机号
     * @param bizId    业务流水号或运营商回执号
     * @param sendDate 短信发送日期（为空时由厂商默认取当日）
     * @param channel  目标发信渠道配置
     * @return SmsDeliveryQueryResult 发送状态查询结果
     */
    default SmsDeliveryQueryResult querySendStatus(String phone, String bizId, LocalDate sendDate, SmsChannel channel) {
        String code = getProviderCode() != null ? getProviderCode() : "unknown";
        return SmsDeliveryQueryResult.unsupported(
                channel != null ? channel.getName() : null,
                getSupplier(),
                "短信驱动 [" + code + "] 暂不支持主动发送状态回查"
        );
    }

    /**
     * 只读查询短信渠道对应云厂商账户余额或剩余条数（不支持的厂商默认返回 {@code supported=false} 契约结果）
     *
     * @param channel 目标发信渠道配置
     * @return SmsBalanceResult 账户余额或余量探查结果
     */
    default SmsBalanceResult queryBalance(SmsChannel channel) {
        String code = getProviderCode() != null ? getProviderCode() : "unknown";
        return SmsBalanceResult.unsupported(
                channel != null ? channel.getName() : null,
                getSupplier(),
                "短信驱动 [" + code + "] 暂不支持账户余额探查"
        );
    }

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
