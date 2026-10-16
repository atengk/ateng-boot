package io.github.atengk.boot.excel.annotation;

import io.github.atengk.boot.excel.enums.DesensitizeType;

import java.lang.annotation.*;

/**
 * Excel 导出动态脱敏注解
 * <p>
 * 标注于导出实体字段上，在导出写出单元格时自动进行掩码转换。
 * 遵循 ADR-0012 架构守卫：源业务对象在 JVM 内存中 100% 保持未脱敏真明文，绝不污染业务数据。
 * </p>
 *
 * @author Ateng
 * @since 2026-10-16
 */
@Target(ElementType.FIELD)
@Retention(RetentionPolicy.RUNTIME)
@Documented
public @interface ExcelSensitive {

    /**
     * 脱敏规则类型（单属性简写默认值）
     * <p>例如：{@code @ExcelSensitive(DesensitizeType.MOBILE_PHONE)}</p>
     */
    DesensitizeType value() default DesensitizeType.CUSTOM;

    /**
     * 脱敏规则类型（显式属性，优先于 {@link #value()}）
     */
    DesensitizeType type() default DesensitizeType.CUSTOM;

    /**
     * 自定义起始包含索引（从 0 开始，仅 CUSTOM 类型生效）
     */
    int startInclude() default 0;

    /**
     * 自定义结束排除索引（仅 CUSTOM 类型生效）
     */
    int endExclude() default 0;

    /**
     * 头部保留明文字符数（仅 KEEP_HEAD_TAIL 类型生效）
     */
    int keepHead() default 0;

    /**
     * 尾部保留明文字符数（仅 KEEP_HEAD_TAIL 类型生效）
     */
    int keepTail() default 0;

    /**
     * 替换掩码字符（默认为 '*'）
     */
    char maskChar() default '*';
}
