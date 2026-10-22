package io.github.atengk.boot.core.properties;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 异步线程池运行时增强配置属性
 * 继承自 {@link AsyncPoolProperties} 作为默认主线程池参数，同时聚合下游多业务独立线程池配置
 *
 * @author Ateng
 * @since 2026-10-16
 */
@Getter
@Setter
@ConfigurationProperties(prefix = "ateng.boot.async")
public class AsyncProperties extends AsyncPoolProperties {

    /**
     * 命名多业务受管线程池配置字典
     */
    private Map<String, AsyncPoolProperties> pools = new LinkedHashMap<>();

    public AsyncProperties() {
        // 主线程池显式初始化专属默认前缀
        setThreadNamePrefix("ateng-async-");
        setVirtualThreadNamePrefix("ateng-vt-async-");
    }
}
