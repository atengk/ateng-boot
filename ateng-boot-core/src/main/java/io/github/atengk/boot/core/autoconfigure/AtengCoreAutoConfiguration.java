package io.github.atengk.boot.core.autoconfigure;

import io.github.atengk.boot.core.util.SpringUtil;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;

/**
 * Core 核心通用底座自动配置类
 * 负责在 Spring 容器就绪后自动装配 SpringUtil 全局静态容器门面，赋予全局开箱即用的上下文访问能力
 *
 * @author Ateng
 * @since 2026-10-17
 */
@AutoConfiguration
@ConditionalOnClass(SpringUtil.class)
public class AtengCoreAutoConfiguration {

    /**
     * 自动装配 SpringUtil 静态门面 Bean
     *
     * @return SpringUtil 实例
     */
    @Bean
    @ConditionalOnMissingBean(SpringUtil.class)
    public SpringUtil springUtil() {
        return new SpringUtil();
    }
}
