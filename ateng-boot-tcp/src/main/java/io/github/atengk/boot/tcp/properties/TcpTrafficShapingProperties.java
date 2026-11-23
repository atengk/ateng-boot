package io.github.atengk.boot.tcp.properties;

import lombok.Getter;
import lombok.Setter;
import org.springframework.util.unit.DataSize;

import java.time.Duration;

/**
 * TCP 网络层流量整形与带宽限速配置属性
 *
 * @author Ateng
 * @since 2026-11-02
 */
@Getter
@Setter
public class TcpTrafficShapingProperties {

    /**
     * 是否启用流量整形与带宽限速（默认 false）
     */
    private boolean enabled = false;

    /**
     * 全局入站读取带宽上限（如 10MB 表示 10MB/s，0 或 null 表示不限速）
     */
    private DataSize globalReadLimit = DataSize.ofBytes(0);

    /**
     * 全局出站写入带宽上限（如 10MB 表示 10MB/s，0 或 null 表示不限速）
     */
    private DataSize globalWriteLimit = DataSize.ofBytes(0);

    /**
     * 单连接入站读取带宽上限（如 1MB 表示 1MB/s，0 或 null 表示不限速）
     */
    private DataSize channelReadLimit = DataSize.ofBytes(0);

    /**
     * 单连接出站写入带宽上限（如 1MB 表示 1MB/s，0 或 null 表示不限速）
     */
    private DataSize channelWriteLimit = DataSize.ofBytes(0);

    /**
     * 带宽统计计算与周期性检查间隔（默认 1s）
     */
    private Duration checkInterval = Duration.ofSeconds(1);

    /**
     * 写入限速的最大等待延迟时间窗口（超出该窗口的消息将直接提交或触发背压，默认 15s）
     */
    private Duration maxTime = Duration.ofSeconds(15);
}
