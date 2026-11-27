package io.github.atengk.boot.web.crypto.annotation;

import io.github.atengk.boot.web.crypto.enums.CryptoAlgorithm;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * API 传输层出站响应体加密注解
 * 可标注于 Controller 类或方法上（方法级优先），支持局部关闭豁免与错误响应加密控制
 *
 * @author Ateng
 * @since 2026-11-27
 */
@Target({ElementType.METHOD, ElementType.TYPE})
@Retention(RetentionPolicy.RUNTIME)
@Documented
public @interface ApiEncrypt {

    /**
     * 是否开启出站加密（类级启用时可在局部方法设为 false 豁免）
     */
    boolean enabled() default true;

    /**
     * 指定当前接口使用的加解密算法（默认 {@link CryptoAlgorithm#DEFAULT} 跟随全局配置）
     */
    CryptoAlgorithm algorithm() default CryptoAlgorithm.DEFAULT;

    /**
     * 异常或错误响应是否也执行加密（默认 false，保留标准明文 Result 错误码便于排障）
     */
    boolean encryptError() default false;

    /**
     * 响应加密失败时的默认业务错误提示
     */
    String message() default "响应体加密失败";
}
