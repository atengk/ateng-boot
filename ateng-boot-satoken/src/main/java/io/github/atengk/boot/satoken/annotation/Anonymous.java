package io.github.atengk.boot.satoken.annotation;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * 声明式匿名公开访问注解
 * <p>
 * 可标注于 Controller 类或方法上。标注后，该路由将被识别为公开免鉴权接口，
 * 系统在启动期会自动提取其映射路径、格式化为 AntPath 通配符并汇入 Sa-Token 路由拦截器白名单中，
 * 实现零运行时反射损耗的纯声明式放行。
 * </p>
 *
 * @author Ateng
 * @since 2026-10-20
 */
@Target({ElementType.METHOD, ElementType.TYPE})
@Retention(RetentionPolicy.RUNTIME)
@Documented
public @interface Anonymous {

    /**
     * 是否开启匿名放行（默认 true，允许显式设置为 false 用于方法级覆盖类级放行）
     *
     * @return true 表示放行
     */
    boolean value() default true;
}
