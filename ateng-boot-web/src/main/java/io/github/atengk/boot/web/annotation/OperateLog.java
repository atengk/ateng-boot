package io.github.atengk.boot.web.annotation;

import io.github.atengk.boot.core.audit.OperateTypeEnum;
import org.springframework.core.annotation.AliasFor;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * 结构化业务操作审计注解
 * 声明在 Controller 方法或类上，自动拦截采集操作人、IP、耗时、出入参（联动视图脱敏）并异步持久化
 *
 * @author Ateng
 * @since 2026-10-18
 */
@Target({ElementType.METHOD, ElementType.TYPE})
@Retention(RetentionPolicy.RUNTIME)
@Documented
public @interface OperateLog {

    /**
     * 模块或操作说明快捷别名（等价于 module）
     *
     * @return 快捷模块说明
     */
    @AliasFor("module")
    String value() default "";

    /**
     * 功能模块名称（如“用户管理”、“订单服务”）
     *
     * @return 模块名称
     */
    @AliasFor("value")
    String module() default "";

    /**
     * 操作标题或业务说明（如“新增用户”、“导出报表”）
     *
     * @return 操作标题
     */
    String title() default "";

    /**
     * 操作动作类型分类（默认 OTHER）
     *
     * @return 操作动作分类
     */
    OperateTypeEnum type() default OperateTypeEnum.OTHER;

    /**
     * 详细业务操作描述
     *
     * @return 业务描述
     */
    String description() default "";

    /**
     * 是否采集并记录请求入参（默认 true）
     *
     * @return true 记录入参，false 忽略
     */
    boolean saveParams() default true;

    /**
     * 是否采集并记录响应出参（默认 true，自动联动 @FieldSensitive 视图脱敏）
     *
     * @return true 记录出参，false 忽略
     */
    boolean saveResult() default true;

    /**
     * 需排除或掩码的敏感请求入参字段名列表（如 password、token、secret 等，不区分大小写）
     *
     * @return 排除入参字段清单
     */
    String[] excludeParams() default {};
}
