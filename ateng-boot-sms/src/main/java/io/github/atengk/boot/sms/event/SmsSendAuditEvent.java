package io.github.atengk.boot.sms.event;

import com.fasterxml.jackson.annotation.JsonIgnore;
import io.github.atengk.boot.sms.model.BatchSmsRequest;
import io.github.atengk.boot.sms.model.BatchSmsResult;
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
     * 批量发信请求值对象（若为单条发信则为 null）
     */
    private final BatchSmsRequest batchRequest;

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
     * 批量发信执行聚合结果（若为单条发信则为 null）
     */
    private final BatchSmsResult batchResult;

    /**
     * 抛出的发信异常因果对象（若正常返回结果则为 null）
     */
    @JsonIgnore
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
        this.batchRequest = null;
        this.channelName = channelName;
        this.supplier = supplier;
        this.result = result;
        this.batchResult = null;
        this.exception = exception;
        this.costMillis = costMillis;
        this.auditTimestamp = auditTimestamp != null ? auditTimestamp : Instant.now();
    }

    /**
     * 批量发信全链路审计事件构造器
     *
     * @param source         事件发布源
     * @param batchRequest   批量发信请求
     * @param channelName    渠道名称
     * @param supplier       服务商类型
     * @param batchResult    批量聚合结果
     * @param exception      执行异常
     * @param costMillis     全链路耗时
     * @param auditTimestamp 审计时间戳
     */
    public SmsSendAuditEvent(Object source,
                             BatchSmsRequest batchRequest,
                             String channelName,
                             SmsSupplier supplier,
                             BatchSmsResult batchResult,
                             Throwable exception,
                             long costMillis,
                             Instant auditTimestamp) {
        super(source);
        this.batchRequest = batchRequest;
        this.batchResult = batchResult;
        this.channelName = channelName;
        this.supplier = supplier;
        this.exception = exception;
        this.costMillis = costMillis;
        this.auditTimestamp = auditTimestamp != null ? auditTimestamp : Instant.now();

        // 构造代表性的单发请求与结果适配对象，保障下游监听器既有方法 100% 兼容
        if (batchRequest != null) {
            this.request = SmsRequest.builder()
                    .phones(batchRequest.getPhones())
                    .templateId(batchRequest.getTemplateId())
                    .signName(batchRequest.getSignName())
                    .channel(channelName)
                    .bizId(batchRequest.getBizId())
                    .urgent(batchRequest.isUrgent())
                    .build();
        } else {
            this.request = null;
        }

        if (batchResult != null && !batchResult.getBatchResults().isEmpty()) {
            this.result = batchResult.getBatchResults().get(0);
        } else if (batchResult != null && batchResult.isSuccess()) {
            this.result = SmsResult.success(channelName, supplier, batchRequest != null ? batchRequest.getBizId() : null, null, batchResult.getSuccessCount());
        } else if (batchResult != null) {
            this.result = SmsResult.blocked(channelName, "批量发信拦截或全部失败");
        } else {
            this.result = null;
        }
    }

    /**
     * 忽略底层 Spring 事件源以防止序列化污染
     *
     * @return 原始事件源对象
     */
    @JsonIgnore
    @Override
    public Object getSource() {
        return super.getSource();
    }

    /**
     * 获取异常原因摘要
     *
     * @return 异常消息
     */
    public String getExceptionMessage() {
        return exception != null ? exception.getMessage() : null;
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
