package io.github.atengk.boot.liteflow.env;

import io.github.atengk.boot.liteflow.thread.LiteflowGlobalExecutorBuilder;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.env.EnvironmentPostProcessor;
import org.springframework.core.Ordered;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.core.env.MapPropertySource;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * LiteFlow 运行环境后置处理器
 * 在 Spring Boot 环境准备极早阶段注入受管异步线程池构建器与生产安全默认配置
 *
 * @author Ateng
 * @since 2026-11-03
 */
public class LiteflowEnvironmentPostProcessor implements EnvironmentPostProcessor, Ordered {

    public static final String PROPERTY_SOURCE_NAME = "atengLiteflowDefaults";

    @Override
    public int getOrder() {
        return Ordered.LOWEST_PRECEDENCE - 10;
    }

    @Override
    public void postProcessEnvironment(ConfigurableEnvironment environment, SpringApplication application) {
        if (environment == null) {
            return;
        }

        Boolean enabled = environment.getProperty("ateng.boot.liteflow.enabled", Boolean.class, true);
        if (Boolean.FALSE.equals(enabled)) {
            return;
        }

        if (!environment.getPropertySources().contains(PROPERTY_SOURCE_NAME)) {
            Map<String, Object> defaults = new LinkedHashMap<>();
            // 1. 全局异步线程池接管为受管构建器
            defaults.put("liteflow.global-thread-pool-executor-class", LiteflowGlobalExecutorBuilder.class.getName());
            // 2. 关闭原生虚拟线程直调，交由受管线程池统一代理四维上下文
            defaults.put("liteflow.enable-virtual-thread", false);
            // 3. 开启多规则类型支持（支持 xml/yml/json）
            defaults.put("liteflow.support-multiple-type", true);
            // 4. 桥接规则扫描默认路径
            defaults.put("liteflow.rule-source", "classpath*:liteflow/*.el.xml");
            environment.getPropertySources().addLast(new MapPropertySource(PROPERTY_SOURCE_NAME, defaults));
        }
    }
}
