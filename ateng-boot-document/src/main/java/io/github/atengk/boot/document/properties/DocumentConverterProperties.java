package io.github.atengk.boot.document.properties;

import lombok.Data;
import org.springframework.boot.context.properties.NestedConfigurationProperty;

import java.time.Duration;

/**
 * 文档跨格式转换基础设施配置属性
 *
 * @author Ateng
 * @since 2026-11-18
 */
@Data
public class DocumentConverterProperties {

    /**
     * Gotenberg 独立文档转换微服务客户端配置
     */
    @NestedConfigurationProperty
    private GotenbergProperties gotenberg = new GotenbergProperties();

    /**
     * Gotenberg 转换服务连接参数
     *
     * @author Ateng
     * @since 2026-11-18
     */
    @Data
    public static class GotenbergProperties {

        /**
         * 是否启用 Gotenberg 转换器适配器，默认关闭，开启后替代空兜底转换器
         */
        private boolean enabled = false;

        /**
         * Gotenberg 服务根地址（例如 http://localhost:3000）
         */
        private String baseUrl = "";

        /**
         * 建立 HTTP 连接超时时长
         */
        private Duration connectTimeout = Duration.ofSeconds(5);

        /**
         * 文档转换 HTTP 读取等待超时时长
         */
        private Duration readTimeout = Duration.ofSeconds(60);
    }
}
