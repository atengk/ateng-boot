package io.github.atengk.boot.web.annotation;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Web 接口审计日志注解
 * 支持标注于 Controller 类或具体方法上，统一记录请求摘要、真实 IP、入参、出参及执行耗时
 *
 * @author Ateng
 * @since 2026-10-16
 */
@Target({ElementType.METHOD, ElementType.TYPE})
@Retention(RetentionPolicy.RUNTIME)
@Documented
public @interface WebLog {

    /**
     * 业务操作描述（如 "用户注册"、"分页查询日志"）
     *
     * @return 业务操作描述
     */
    String value() default "";

    /**
     * 是否打印请求入参
     *
     * @return true 表示打印入参，默认 true
     */
    boolean printParams() default true;

    /**
     * 是否打印响应出参
     *
     * @return true 表示打印出参，默认 true
     */
    boolean printResult() default true;

    /**
     * 是否统计并打印执行耗时（毫秒）
     *
     * @return true 表示统计并打印耗时，默认 true
     */
    boolean printCostTime() default true;

    /**
     * 显式排除的入参参数名列表（如 password、token、secret 等敏感字段，防止凭据泄露进日志）
     *
     * @return 排除的入参名数组
     */
    String[] excludeParams() default {};
}
