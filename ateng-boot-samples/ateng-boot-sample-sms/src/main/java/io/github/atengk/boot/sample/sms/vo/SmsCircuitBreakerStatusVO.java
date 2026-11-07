package io.github.atengk.boot.sample.sms.vo;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;

/**
 * 渠道熔断健康度状态对外展现视图对象
 *
 * @author Ateng
 * @since 2026-11-07
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class SmsCircuitBreakerStatusVO implements Serializable {

    private static final long serialVersionUID = 1L;

    /**
     * 渠道名称
     */
    private String channel;

    /**
     * 熔断器是否处于打开状态 (OPEN)
     */
    private boolean isOpen;

    /**
     * 熔断器状态字符串 (OPEN / CLOSED)
     */
    private String status;
}
