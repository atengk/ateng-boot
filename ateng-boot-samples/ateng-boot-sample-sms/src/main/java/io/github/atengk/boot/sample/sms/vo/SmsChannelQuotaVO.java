package io.github.atengk.boot.sample.sms.vo;

import io.github.atengk.boot.sms.model.SmsQuotaResetCycle;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;

/**
 * 短信单渠道配额状态视图对象
 *
 * @author Ateng
 * @since 2026-11-21
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class SmsChannelQuotaVO implements Serializable {

    private static final long serialVersionUID = 1L;

    /**
     * 渠道名称
     */
    private String channelName;

    /**
     * 单渠道配额上限（0 表示不限制）
     */
    private long maxQuota;

    /**
     * 当前周期内已使用配额
     */
    private long usedQuota;

    /**
     * 剩余可用配额（-1 表示无上限）
     */
    private long remainingQuota;

    /**
     * 配额重置周期
     */
    private SmsQuotaResetCycle quotaResetCycle;
}
