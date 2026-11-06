package io.github.atengk.boot.ai.annotation;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * 声明式大模型智能体用户标识入参注解
 * <p>
 * 标注于智能体接口方法的入参上，指示该入参对应目标用户唯一身份标识（如 userId / openId / accountId）。
 * 用于长效情景记忆 (Episodic Memory) 的画像精准检索与事实沉淀。
 * </p>
 *
 * @author Ateng
 * @since 2026-11-06
 */
@Target(ElementType.PARAMETER)
@Retention(RetentionPolicy.RUNTIME)
@Documented
public @interface UserId {
}
