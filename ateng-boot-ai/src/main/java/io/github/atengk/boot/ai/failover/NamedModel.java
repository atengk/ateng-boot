package io.github.atengk.boot.ai.failover;

import org.springframework.util.StringUtils;

import java.util.Objects;

/**
 * 带有语义标识的大模型候选实例封装
 *
 * @param <T> 模型类型 (如 ChatLanguageModel 或 StreamingChatLanguageModel)
 * @author Ateng
 * @since 2026-11-05
 */
public record NamedModel<T>(String name, T model) {

    public NamedModel {
        name = StringUtils.hasText(name) ? name.trim() : "unnamed";
        Objects.requireNonNull(model, "候选大模型实例不能为空");
    }

    /**
     * 静态工厂便捷构造方法
     *
     * @param name  模型语义名称或别名
     * @param model 模型实例
     * @param <T>   模型类型
     * @return NamedModel 实例
     */
    public static <T> NamedModel<T> of(String name, T model) {
        return new NamedModel<>(name, model);
    }
}
