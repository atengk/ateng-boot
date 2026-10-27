package io.github.atengk.boot.web.ip.annotation;

import io.github.atengk.boot.web.ip.IpAccessMode;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * 声明式 IP 访问控制注解
 * 支持标注于 Controller 类或方法上，提供基于单个 IP 与 IPv4/IPv6 CIDR 子网的白名单/黑名单校验与快速拒绝
 *
 * @author Ateng
 * @since 2026-10-27
 */
@Target({ElementType.METHOD, ElementType.TYPE})
@Retention(RetentionPolicy.RUNTIME)
@Documented
public @interface IpAccessControl {

    /**
     * IP 访问控制校验模式（默认 WHITE_LIST 白名单模式）
     *
     * @return 访问控制模式
     */
    IpAccessMode mode() default IpAccessMode.WHITE_LIST;

    /**
     * IP / CIDR 规则清单（例如 127.0.0.1, 192.168.1.0/24, 10.0.0.0/8, ::1, 2001:db8::/32）
     *
     * @return 规则列表
     */
    String[] rules() default {};

    /**
     * 自定义拒绝提示文案（为空时使用全局配置默认文案）
     *
     * @return 拒绝提示文案
     */
    String message() default "";

    /**
     * 是否启用 HTTP 403 物理切断模式（默认 false，遵循 ADR-0004 返回 HTTP 200 + Result.fail(403)）
     *
     * @return 是否物理切断
     */
    boolean httpStatus403() default false;
}
