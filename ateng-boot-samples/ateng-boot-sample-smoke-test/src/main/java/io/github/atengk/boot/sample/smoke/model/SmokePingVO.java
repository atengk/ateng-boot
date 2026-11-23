package io.github.atengk.boot.sample.smoke.model;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serial;
import java.io.Serializable;

/**
 * 冒烟探活响应视图对象
 *
 * @author Ateng
 * @since 2026-11-12
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class SmokePingVO implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    /**
     * 服务名称
     */
    private String serviceName;

    /**
     * 框架版本号
     */
    private String version;

    /**
     * 大整数雪花 ID 精度探测值（超过 JS IEEE 754 安全整数上限，验证 long-to-string 自动转字符串）
     */
    private Long snowflakeProbeId;

    /**
     * 当前请求链路追踪标识
     */
    private String traceId;
}
