package io.github.atengk.boot.sample.mail.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;
import java.util.Map;

/**
 * 邮件可观测健康探针与 Micrometer 指标快照视图对象
 *
 * @author Ateng
 * @since 2026-11-16
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ObservabilitySnapshotResponse implements Serializable {

    private static final long serialVersionUID = 1L;

    /**
     * MailSafeHealthIndicator 健康状态码（UP / DOWN / UNKNOWN）
     */
    private String healthStatus;

    /**
     * 健康探针脱敏详情
     */
    private Map<String, Object> healthDetails;

    /**
     * 静态注册账号数量（Gauge）
     */
    private double staticAccountCount;

    /**
     * 动态缓存账号数量（Gauge）
     */
    private double dynamicAccountCount;

    /**
     * 发信总调用计数
     */
    private double totalSendCount;

    /**
     * 物理发信成功计数
     */
    private double successCount;

    /**
     * 沙箱纯日志模拟计数
     */
    private double sandboxCount;

    /**
     * 发信失败/拦截总计数
     */
    private double nonSuccessCount;

    /**
     * 物理重试计数
     */
    private double retryCount;

    /**
     * 主备容灾切换计数
     */
    private double failoverCount;
}
