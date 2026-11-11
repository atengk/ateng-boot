package io.github.atengk.boot.sample.admin.dto;

import lombok.Builder;
import lombok.Data;

import java.time.Instant;

/**
 * 受管实例脱敏摘要响应 VO（严守 Zero-Entity-Leakage，绝不暴露 SBA Instance 原生对象）
 *
 * @author Ateng
 * @since 2026-11-11
 */
@Data
@Builder
public class AdminInstanceSummaryVO {

    /**
     * 实例唯一 ID
     */
    private String instanceId;

    /**
     * 微服务名称
     */
    private String serviceName;

    /**
     * 实例当前健康状态
     */
    private String status;

    /**
     * 是否处于已注册在网状态
     */
    private boolean registered;

    /**
     * 微服务根路径地址
     */
    private String serviceUrl;

    /**
     * 应用版本元数据
     */
    private String version;

    /**
     * 部署可用区标签
     */
    private String zone;

    /**
     * 最近状态更新时间
     */
    private Instant statusTimestamp;
}
