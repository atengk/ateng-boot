package io.github.atengk.boot.mybatisplus.annotation;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * 实体复合冲突键（唯一键/唯一索引）声明注解
 * <p>
 * 用于在 PostgreSQL 方言下执行原生批量 Upsert 操作时，明确指定生成 {@code ON CONFLICT (col1, col2) DO UPDATE SET}
 * 冲突子句的目标冲突列。若未标注本注解，默认回退至实体的 {@code @TableId} 主键列。
 * </p>
 *
 * @author Ateng
 * @since 2026-10-19
 */
@Documented
@Target(ElementType.TYPE)
@Retention(RetentionPolicy.RUNTIME)
public @interface ConflictKeys {

    /**
     * 冲突目标列名或实体属性名数组（例如 {@code {"user_id", "role_id"}} 或 {@code {"userId", "roleId"}}）
     *
     * @return 冲突目标列名或属性名列表
     */
    String[] value();
}
