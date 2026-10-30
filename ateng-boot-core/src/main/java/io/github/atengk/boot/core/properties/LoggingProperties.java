package io.github.atengk.boot.core.properties;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.NestedConfigurationProperty;
import org.springframework.util.unit.DataSize;

/**
 * 核心日志引擎与持久化配置属性
 * 提供控制台高亮彩色模式、traceId 追踪与零 XML 磁盘双文件归档切片配置
 *
 * @author Ateng
 * @since 2026-10-30
 */
@Getter
@Setter
public class LoggingProperties {

    /**
     * 日志引擎总开关（默认 true，为 false 时完全保留 Spring 官方原生裸行为）
     */
    private boolean enabled = true;

    /**
     * 控制台日志配置
     */
    @NestedConfigurationProperty
    private ConsoleProperties console = new ConsoleProperties();

    /**
     * 文件持久化落盘配置
     */
    @NestedConfigurationProperty
    private FileProperties file = new FileProperties();

    /**
     * 控制台日志配置
     */
    @Getter
    @Setter
    public static class ConsoleProperties {

        /**
         * 是否开启高亮彩色 + traceId 控制台输出（默认 true）
         */
        private boolean enabled = true;
    }

    /**
     * 文件持久化配置
     */
    @Getter
    @Setter
    public static class FileProperties {

        /**
         * 是否开启磁盘文件持久化落盘（默认 false）
         */
        private boolean enabled = false;

        /**
         * 日志文件输出目录路径（默认 logs）
         */
        private String path = "logs";

        /**
         * 单日志文件滚动切片阈值（默认 100MB）
         */
        private DataSize maxFileSize = DataSize.ofMegabytes(100);

        /**
         * 历史归档日志保留天数（默认 30 天）
         */
        private int maxHistory = 30;

        /**
         * 磁盘日志总占用上限（默认 10GB，超限清理最老归档）
         */
        private DataSize totalSizeCap = DataSize.ofGigabytes(10);

        /**
         * 是否独立分流生成 app-error.log 错误日志（默认 true）
         */
        private boolean splitError = true;
    }
}
