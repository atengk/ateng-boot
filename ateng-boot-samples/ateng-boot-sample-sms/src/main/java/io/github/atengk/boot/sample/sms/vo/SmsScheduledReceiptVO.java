package io.github.atengk.boot.sample.sms.vo;

import io.github.atengk.boot.sms.delay.SmsScheduledReceipt;
import io.github.atengk.boot.sms.delay.SmsScheduledStatus;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;

/**
 * 延迟或定时短信调度回执视图对象
 *
 * @author Ateng
 * @since 2026-11-21
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class SmsScheduledReceiptVO {

    private String taskId;
    private Instant triggerAt;
    private String channelName;
    private boolean batch;
    private int phoneCount;
    private SmsScheduledStatus status;

    public static SmsScheduledReceiptVO fromReceipt(SmsScheduledReceipt receipt) {
        if (receipt == null) {
            return null;
        }
        return SmsScheduledReceiptVO.builder()
                .taskId(receipt.getTaskId())
                .triggerAt(receipt.getTriggerAt())
                .channelName(receipt.getChannelName())
                .batch(receipt.isBatch())
                .phoneCount(receipt.getPhoneCount())
                .status(receipt.getStatus())
                .build();
    }
}
