package io.github.atengk.boot.sample.sms.vo;

import io.github.atengk.boot.core.util.DesensitizeUtil;
import io.github.atengk.boot.sms.model.SmsDeliveryQueryResult;
import io.github.atengk.boot.sms.model.SmsDeliveryStatus;
import io.github.atengk.boot.sms.model.SmsSupplier;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 主动短信送达状态查询响应视图对象（手机号强制脱敏展示）
 *
 * @author Ateng
 * @since 2026-11-21
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class SmsDeliveryQueryVO {

    private boolean supported;
    private String channelName;
    private SmsSupplier supplier;
    private String maskedPhone;
    private String bizId;
    private SmsDeliveryStatus deliveryStatus;
    private String rawStatusCode;
    private String rawStatusDesc;
    private String message;

    public static SmsDeliveryQueryVO fromResult(SmsDeliveryQueryResult res) {
        if (res == null) {
            return null;
        }
        return SmsDeliveryQueryVO.builder()
                .supported(res.isSupported())
                .channelName(res.getChannelName())
                .supplier(res.getSupplier())
                .maskedPhone(DesensitizeUtil.mobilePhone(res.getPhone()))
                .bizId(res.getBizId())
                .deliveryStatus(res.getDeliveryStatus())
                .rawStatusCode(res.getRawStatusCode())
                .rawStatusDesc(res.getRawStatusDesc())
                .message(res.getMessage())
                .build();
    }
}
