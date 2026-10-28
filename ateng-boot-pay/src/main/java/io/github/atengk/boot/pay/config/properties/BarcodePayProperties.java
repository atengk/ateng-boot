package io.github.atengk.boot.pay.config.properties;

import lombok.Data;

import java.io.Serializable;
import java.time.Duration;

/**
 * 线下收银台条码付轮询与主动撤销执行器配置属性
 *
 * @author Ateng
 * @since 2026-10-24
 */
@Data
public class BarcodePayProperties implements Serializable {

    private static final long serialVersionUID = 1L;

    /**
     * 等待用户输密最大轮询超时时间，默认 30 秒（强类型 Duration 规范）
     */
    private Duration maxWaitTime = Duration.ofSeconds(30);

    /**
     * 轮询查单间隔步长，默认 3 秒（强类型 Duration 规范）
     */
    private Duration pollInterval = Duration.ofSeconds(3);

    /**
     * 超时未完成或未决时是否自动发起撤销冲正，默认 true
     */
    private boolean autoCancelOnTimeout = true;
}
