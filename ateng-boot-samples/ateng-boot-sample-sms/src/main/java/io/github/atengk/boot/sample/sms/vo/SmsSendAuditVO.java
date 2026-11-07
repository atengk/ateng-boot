package io.github.atengk.boot.sample.sms.vo;

import io.github.atengk.boot.sms.event.SmsSendAuditEvent;
import io.github.atengk.boot.sms.model.SmsSupplier;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;
import java.time.Instant;
import java.util.List;

/**
 * 全链路发信审计事件对外展现视图对象
 *
 * @author Ateng
 * @since 2026-11-07
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class SmsSendAuditVO implements Serializable {

    private static final long serialVersionUID = 1L;

    /**
     * 单发接收手机号
     */
    private String phone;

    /**
     * 批量发信手机号清单
     */
    private List<String> phones;

    /**
     * 短信模板标识
     */
    private String templateId;

    /**
     * 发信渠道名称
     */
    private String channelName;

    /**
     * 底层短信服务商类型
     */
    private SmsSupplier supplier;

    /**
     * 发信是否最终成功
     */
    private boolean success;

    /**
     * 是否被出站守卫规则拦截
     */
    private boolean blocked;

    /**
     * 是否投递失败
     */
    private boolean failed;

    /**
     * 发信耗时（毫秒）
     */
    private long costMillis;

    /**
     * 审计事件发生时间戳
     */
    private Instant auditTimestamp;

    /**
     * 失败原因或异常描述
     */
    private String exceptionMessage;

    /**
     * 从领域审计事件转换为传输视图对象
     *
     * @param event 全链路发信审计事件
     * @return 审计视图对象
     */
    public static SmsSendAuditVO fromEvent(SmsSendAuditEvent event) {
        if (event == null) {
            return null;
        }
        return SmsSendAuditVO.builder()
                .phone(event.getPhone())
                .phones(event.getPhones())
                .templateId(event.getTemplateId())
                .channelName(event.getChannelName())
                .supplier(event.getSupplier())
                .success(event.isSuccess())
                .blocked(event.isBlocked())
                .failed(event.isFailed())
                .costMillis(event.getCostMillis())
                .auditTimestamp(event.getAuditTimestamp())
                .exceptionMessage(event.getExceptionMessage())
                .build();
    }
}
