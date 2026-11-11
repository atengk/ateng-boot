package io.github.atengk.boot.sample.tcp.dto;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;

/**
 * 全局流量整形（GlobalTrafficShapingHandler）实时限速动态调节请求 DTO
 *
 * @author Ateng
 * @since 2026-11-11
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class TrafficShapingAdjustRequest implements Serializable {

    private static final long serialVersionUID = 1L;

    /**
     * 服务端注册实例名
     */
    @NotBlank(message = "服务端实例名 serverName 不能为空")
    @Builder.Default
    private String serverName = "default";

    /**
     * 全局出站写入限速（字节/秒，0 表示不限制）
     */
    @NotNull(message = "全局写入限速 writeLimitBytesPerSec 不能为空")
    @Min(value = 0, message = "写入限速不能小于 0")
    private Long writeLimitBytesPerSec;

    /**
     * 全局入站读取限速（字节/秒，0 表示不限制）
     */
    @NotNull(message = "全局读取限速 readLimitBytesPerSec 不能为空")
    @Min(value = 0, message = "读取限速不能小于 0")
    private Long readLimitBytesPerSec;
}
