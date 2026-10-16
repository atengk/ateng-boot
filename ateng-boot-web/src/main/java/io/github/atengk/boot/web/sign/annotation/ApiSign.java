package io.github.atengk.boot.web.sign.annotation;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * API 双向数据加签验签与防重放注解
 * 可标注于 Controller 类或方法，声明开启入站请求验签与出站响应加签
 *
 * @author Ateng
 * @since 2026-10-16
 */
@Target({ElementType.METHOD, ElementType.TYPE})
@Retention(RetentionPolicy.RUNTIME)
@Documented
public @interface ApiSign {

    /**
     * 是否开启入站请求验签
     * 为 true 时校验 X-Timestamp、X-Nonce 与 X-Signature 防篡改及防重放
     */
    boolean verifyRequest() default true;

    /**
     * 是否开启出站响应加签
     * 为 true 时对 Controller 返回的 Result<?> 计算数字签名并在 X-Signature 响应头回写
     */
    boolean signResponse() default true;

    /**
     * 请求时效防重放时间窗口（秒，默认 60 秒）
     */
    long expireSeconds() default 60L;

    /**
     * 验签或防重放失败时的异常提示信息
     */
    String message() default "数据验签失败";
}
