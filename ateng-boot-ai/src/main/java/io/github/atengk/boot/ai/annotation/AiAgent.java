package io.github.atengk.boot.ai.annotation;

import org.springframework.core.annotation.AliasFor;

import java.lang.annotation.*;

/**
 * 声明式大模型智能体（AI Agent）接口契约注解
 * 标注于 Java 接口上，由框架在 Spring 容器启动期动态注册其代理实现 Bean。
 *
 * @author Ateng
 * @since 2026-11-03
 */
@Target(ElementType.TYPE)
@Retention(RetentionPolicy.RUNTIME)
@Documented
public @interface AiAgent {

    /**
     * 绑定的命名模型标识（对应 ateng.boot.ai.models.<name>）
     * 为空时使用默认全局模型配置，且服从 AiContextHolder 线程级动态切源优先规则
     *
     * @return 绑定的模型名称
     */
    @AliasFor("model")
    String value() default "";

    /**
     * 绑定的命名模型标识
     *
     * @return 绑定的模型名称
     */
    @AliasFor("value")
    String model() default "";

    /**
     * 局部绑定的受管工具类列表（方法需标注 @Tool）
     * 必须受 Spring 容器托管（未找到 Bean 启动期 Fail-Fast）
     *
     * @return 工具类 Class 数组
     */
    Class<?>[] tools() default {};

    /**
     * 外部知识库/向量检索 ContentRetriever Bean 名称
     *
     * @return ContentRetriever Bean 名称
     */
    String contentRetriever() default "";

    /**
     * 会话记忆 ChatMemory 或 ChatMemoryProvider Bean 名称
     *
     * @return 会话记忆 Bean 名称
     */
    String chatMemory() default "";
}
