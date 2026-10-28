package io.github.atengk.boot.search.annotation;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * 搜索引擎高亮匹配富文本回填注解
 * <p>
 * 标注于视图传输对象 (VO) 或实体字段上。当执行搜索匹配时，底座自动提取命中文档对应字段的高亮片段回填至此属性中，
 * 严格恪守内存真明文原则，仅在输出视图边界进行呈现渲染。
 *
 * @author Ateng
 * @since 2026-10-25
 */
@Target(ElementType.FIELD)
@Retention(RetentionPolicy.RUNTIME)
@Documented
public @interface HighlightField {

    /**
     * 关联的目标搜索高亮字段名
     * <p>
     * 若为空字符串，则默认对齐当前被注解属性的字段名。
     *
     * @return 目标字段名
     */
    String target() default "";
}
