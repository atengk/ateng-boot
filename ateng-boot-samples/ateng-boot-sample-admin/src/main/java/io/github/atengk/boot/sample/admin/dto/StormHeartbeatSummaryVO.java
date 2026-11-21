package io.github.atengk.boot.sample.admin.dto;

import lombok.Builder;
import lombok.Data;

import java.time.Duration;
import java.time.Instant;
import java.util.Map;

/**
 * 监控中心雪崩风暴聚合、持续重提醒与客户端心跳汇总视图 VO
 *
 * @author Ateng
 * @since 2026-11-21
 */
@Data
@Builder
public class StormHeartbeatSummaryVO {

    private long registeredInstances;
    private long upInstances;
    private Map<String, Long> statusCounts;
    private Instant lastHeartbeatTimestamp;
    private int pendingAlertTasks;
    private int activeAlertedInstances;
    private int activeSilenceRules;
    private boolean stormAggregationEnabled;
    private Duration stormWindow;
    private int stormThreshold;
    private int currentStormBucketCount;
    private boolean reminderEnabled;
    private Duration reminderInterval;
    private int reminderMaxCount;
    private boolean clusterEnabled;
}
