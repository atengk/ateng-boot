package io.github.atengk.boot.sample.mail.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;
import java.util.List;

/**
 * 出站治理演示统一响应视图对象
 *
 * @author Ateng
 * @since 2026-11-16
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class GuardDemoResponse implements Serializable {

    private static final long serialVersionUID = 1L;

    /**
     * 成功放行发信次数
     */
    private int allowedCount;

    /**
     * 被治理策略拦截阻断次数
     */
    private int blockedCount;

    /**
     * 触发拦截时的业务错误码（如 40007 / 40008 / 40009）
     */
    private Integer triggeredResultCode;

    /**
     * 是否捕获到 MailQuotaExceededEvent 领域事件
     */
    private boolean quotaEventCaptured;

    /**
     * 重试或恢复阶段是否成功
     */
    private boolean recoveredAfterResetOrRelease;

    /**
     * 成功外发的 Message-ID 列表
     */
    private List<String> messageIds;

    /**
     * 场景执行摘要说明
     */
    private String summary;
}
