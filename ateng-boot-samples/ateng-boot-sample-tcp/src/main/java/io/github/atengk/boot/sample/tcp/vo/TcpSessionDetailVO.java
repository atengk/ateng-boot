package io.github.atengk.boot.sample.tcp.vo;

import io.github.atengk.boot.sample.tcp.model.VehicleStatusVO;

import java.io.Serializable;
import java.util.Map;

/**
 * 单台车机长连接与遥测状态聚合详情视图对象
 *
 * @author Ateng
 * @since 2026-11-11
 */
public record TcpSessionDetailVO(
        TcpSessionInfoVO session,
        VehicleStatusVO telemetry,
        Map<String, Object> actuatorSnapshot
) implements Serializable {}
