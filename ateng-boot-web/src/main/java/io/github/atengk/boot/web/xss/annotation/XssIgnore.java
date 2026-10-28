package io.github.atengk.boot.web.xss.annotation;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * XSS 安全清洗放行与豁免注解
 * <p>
 * 可声明于 Controller DTO 实体属性、方法或类上：
 * <ul>
 *   <li>声明于属性/方法：针对指定富文本字段（如公告内容、富文本 HTML、Markdown 等），在 Jackson 反序列化时跳过 XSS 清洗；</li>
 *   <li>声明于类：针对该 DTO 实体下的所有字符串属性统一跳过 XSS 清洗；</li>
 * </ul>
 *
 * @author Ateng
 * @since 2026-10-27
 */
@Target({ElementType.FIELD, ElementType.METHOD, ElementType.TYPE})
@Retention(RetentionPolicy.RUNTIME)
@Documented
public @interface XssIgnore {
}
