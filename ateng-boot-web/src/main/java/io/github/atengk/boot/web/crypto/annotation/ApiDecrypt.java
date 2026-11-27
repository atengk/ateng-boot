package io.github.atengk.boot.web.crypto.annotation;

import io.github.atengk.boot.web.crypto.enums.CryptoAlgorithm;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * API 传输层入站请求体解密注解
 * 可标注于 Controller 类或方法上（方法级优先），支持局部关闭豁免与灰度明文兼容
 *
 * @author Ateng
 * @since 2026-11-27
 */
@Target({ElementType.METHOD, ElementType.TYPE})
@Retention(RetentionPolicy.RUNTIME)
@Documented
public @interface ApiDecrypt {

    /**
     * 是否开启入站解密（类级启用时可在局部方法设为 false 豁免）
     */
    boolean enabled() default true;

    /**
     * 指定当前接口使用的加解密算法（默认 {@link CryptoAlgorithm#DEFAULT} 跟随全局配置）
     */
    CryptoAlgorithm algorithm() default CryptoAlgorithm.DEFAULT;

    /**
     * 是否强制要求请求必须为密文（设为 false 时若未携带加密请求头则按明文灰度放行）
     */
    boolean required() default true;

    /**
     * 请求解密失败时的默认业务错误提示
     */
    String message() default "请求体解密失败";
}
