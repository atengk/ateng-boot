package io.github.atengk.boot.core.properties;

import lombok.Getter;
import lombok.Setter;

import java.time.Duration;

/**
 * 方法级慢调用告警配置属性
 * 提供方法执行耗时判定门限、入参快照与防 OOM 截断长度等强类型配置
 *
 * @author Ateng
 * @since 2026-10-30
 */
@Getter
@Setter
public class SlowAlertProperties {

    /**
     * 是否启用 @SlowAlert 慢调用告警切面（默认 true）
     */
    private boolean enabled = true;

    /**
     * 默认慢方法判定耗时阈值（默认 500ms）
     */
    private Duration defaultThreshold = Duration.ofMillis(500);

    /**
     * 告警日志中是否记录方法入参快照（默认 true）
     */
    private boolean includeArgs = true;

    /**
     * 入参快照最大字符截断长度（默认 1024 字符 = 1KB，防大报文引发 OOM）
     */
    private int maxArgsLength = 1024;
}
