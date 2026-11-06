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

    /**
     * 是否自动挂载长效情景记忆 (Episodic Memory)
     * <p>
     * 开启后，智能体将在调用前依据三级探测回溯用户身份并检索长效记忆画像注入系统提示词，
     * 并在调用结束后异步提炼新的用户事实并持久化。
     * </p>
     *
     * @return 是否启用长效情景记忆，默认 false
     */
    boolean episodicMemory() default false;

    /**
     * 在启用 long-term 情景记忆时，是否在对话完成后自动异步提炼并沉淀新事实
     * <p>
     * 设为 false 时仅在调用前执行事实画像检索注入（适用于纯只读或评测场景），不反写提炼新事实。
     * </p>
     *
     * @return 是否自动提炼记忆，默认 true
     */
    boolean extractMemory() default true;
}
