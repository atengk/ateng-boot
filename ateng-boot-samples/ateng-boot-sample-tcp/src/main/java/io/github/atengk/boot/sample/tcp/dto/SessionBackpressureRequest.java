package io.github.atengk.boot.sample.tcp.dto;

import io.github.atengk.boot.tcp.enums.BackpressurePolicy;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;

/**
 * 会话出站背压流控策略与阻塞超时动态调整请求 DTO
 *
 * @author Ateng
 * @since 2026-11-11
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class SessionBackpressureRequest implements Serializable {

    private static final long serialVersionUID = 1L;

    /**
     * 目标设备唯一编号
     */
    @NotBlank(message = "设备编号 deviceId 不能为空")
    private String deviceId;

    /**
     * 期望切换的背压策略（FAIL_FAST / BLOCK_TIMEOUT / WARN_AND_SEND）
     */
    @NotNull(message = "背压策略 policy 不能为空")
    private BackpressurePolicy policy;

    /**
     * 阻塞等待最大超时时间（毫秒）
     */
    @NotNull(message = "超时时间 timeoutMillis 不能为空")
    @Min(value = 0, message = "超时时间不能为负数")
    @Max(value = 60000, message = "超时时间不能超过 60000ms")
    private Long timeoutMillis;
}
