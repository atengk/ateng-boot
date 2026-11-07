package io.github.atengk.boot.sms.event;

import io.github.atengk.boot.sms.model.SmsDeliveryStatus;
import io.github.atengk.boot.sms.model.SmsRequest;
import io.github.atengk.boot.sms.model.SmsResult;
import io.github.atengk.boot.sms.model.SmsSupplier;
import lombok.Getter;
import lombok.ToString;
import org.springframework.context.ApplicationEvent;

import java.time.Instant;
import java.util.Collections;
import java.util.List;

/**
 * 全链路出站短信发信审计领域事件
 * 在每次单条短信发信结束（成功投递、顺延降级成功、前置静默拦截、最终失败）时向 Spring 容器广播
 * 供下游审计日志归档、全链路耗时监控、Prometheus 监控指标埋点及异常告警订阅
 *
 * @author Ateng
 * @since 2026-11-07
 */
@Getter
@ToString(callSuper = true)
public class SmsSendAuditEvent extends ApplicationEvent {

    private static final long serialVersionUID = 1L;

    /**
     * 原始发信请求值对象
     */
    private final SmsRequest request;

    /**
     * 决策执行的目标渠道标识
     */
    private final String channelName;

    /**
     * 云厂商供应商类型
     */
    private final SmsSupplier supplier;

    /**
     * 发信执行结果（为 null 表示未产生正常结果即抛出底层不可控异常）
     */
    private final SmsResult result;

    /**
     * 抛出的发信异常因果对象（若正常返回结果则为 null）
     */
    private final Throwable exception;

    /**
     * 发信物理执行全链路耗时（毫秒）
     */
    private final long costMillis;

    /**
     * 审计事件发生时间戳
     */
    private final Instant auditTimestamp;

    public SmsSendAuditEvent(Object source,
                             SmsRequest request,
                             String channelName,
                             SmsSupplier supplier,
                             SmsResult result,
                             Throwable exception,
                             long costMillis,
                             Instant auditTimestamp) {
        super(source);
        this.request = request;
        this.channelName = channelName;
        this.supplier = supplier;
        this.result = result;
        this.exception = exception;
        this.costMillis = costMillis;
        this.auditTimestamp = auditTimestamp != null ? auditTimestamp : Instant.now();
    }

    /**
     * 判断发信是否成功
     *
     * @return 是否成功
     */
    public boolean isSuccess() {
        return result != null && result.isSuccess();
    }

    /**
     * 判断是否被前置策略拦截（黑名单、夜间免打扰等）
     *
     * @return 是否静默拦截
     */
    public boolean isBlocked() {
        return result != null && result.getDeliveryStatus() == SmsDeliveryStatus.BLOCKED;
    }

    /**
     * 判断发信是否失败（抛出异常或显式返回失败）
     *
     * @return 是否失败
     */
    public boolean isFailed() {
        return exception != null || (result != null && !result.isSuccess());
    }

    /**
     * 获取目标手机号
     *
     * @return 手机号
     */
    public String getPhone() {
        return request != null ? request.getPhone() : null;
    }

    /**
     * 获取目标手机号列表
     *
     * @return 手机号列表
     */
    public List<String> getPhones() {
        return request != null ? request.getPhones() : Collections.emptyList();
    }

    /**
     * 获取发信模板编码
     *
     * @return 模板编码
     */
    public String getTemplateId() {
        return request != null ? request.getTemplateId() : null;
    }
}
