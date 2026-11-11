package io.github.atengk.boot.sample.admin.dto;

import lombok.Builder;
import lombok.Data;

import java.time.Duration;
import java.util.List;

/**
 * 告警防抖与发版静默状态机快照响应 VO
 *
 * @author Ateng
 * @since 2026-11-11
 */
@Data
@Builder
public class AlertStateSnapshotVO {

    /**
     * 当前配置的故障防抖窗口时长
     */
    private Duration debounceDuration;

    /**
     * 处于防抖倒计时等待中的实例数量
     */
    private int pendingCount;

    /**
     * 处于防抖等待中的实例 ID 列表
     */
    private List<String> pendingInstanceIds;

    /**
     * 已确认故障并触发过宕机告警的实例 ID 列表
     */
    private List<String> alertedInstanceIds;

    /**
     * 处于发版静默窗口内的已注销实例 ID 列表
     */
    private List<String> deregisteredSilentInstanceIds;

    /**
     * 当前生效的告警服务白名单匹配模式
     */
    private List<String> includedServices;

    /**
     * 当前生效的告警服务黑名单匹配模式
     */
    private List<String> excludedServices;

    /**
     * 当前触发告警的目标健康状态集合
     */
    private List<String> notifyStatuses;
}
