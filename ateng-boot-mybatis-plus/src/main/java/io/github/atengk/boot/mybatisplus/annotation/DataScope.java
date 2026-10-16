package io.github.atengk.boot.mybatisplus.annotation;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * 细粒度声明式数据权限过滤注解
 * 支持标注在 Mapper 接口类或具体方法上，配合 DataPermissionHandler SPI 在 SQL 语法树级别实现自动条件追加
 *
 * @author Ateng
 * @since 2026-10-16
 */
@Documented
@Target({ElementType.TYPE, ElementType.METHOD})
@Retention(RetentionPolicy.RUNTIME)
public @interface DataScope {

    /**
     * 目标数据表别名（多表关联 JOIN 场景下显式指定，如 "u"；单表或无别名时保持默认留空即可）
     *
     * @return 目标表别名
     */
    String alias() default "";

    /**
     * 组织部门字段列名，默认 dept_id
     *
     * @return 部门字段列名
     */
    String deptColumn() default "dept_id";

    /**
     * 用户主体字段列名，默认 user_id
     *
     * @return 用户字段列名
     */
    String userColumn() default "user_id";

    /**
     * 是否忽略数据权限过滤，默认 false
     * 常用于类级别声明了 @DataScope 时，类中某个特定查询方法需要豁免权限过滤
     *
     * @return true 表示跳过权限过滤
     */
    boolean ignore() default false;

    /**
     * 自定义权限范围业务标识（例如 "order"、"customer" 等，供 DataPermissionHandler SPI 识别特定业务策略）
     *
     * @return 业务权限标识
     */
    String type() default "";
}
