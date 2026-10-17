package io.github.atengk.boot.excel.annotation;

import java.lang.annotation.*;

/**
 * Excel 多媒体图片自适应内嵌导出注解
 * <p>
 * 标注于实体的图片属性字段（支持 {@code String} 网络 HTTP/HTTPS URL、Base64 字符串、本地绝对路径，以及 {@code byte[]}、{@code URL}、{@code File} 等），
 * 自动联动图片转换与行高自适应补偿，彻底消除原生图片压扁或溢出缺陷。
 * </p>
 *
 * @author Ateng
 * @since 2026-10-17
 */
@Target(ElementType.FIELD)
@Retention(RetentionPolicy.RUNTIME)
@Documented
public @interface ExcelImage {

    /**
     * 图片所在行的自适应行高（单位 pt，默认 60）
     * <p>
     * 当实体包含该注解时，导出行将自动撑开至该高度，保障图片与文字优雅排版。
     * </p>
     *
     * @return 目标行高
     */
    short rowHeight() default 60;

    /**
     * 网络图片 HTTP 连接超时时间（单位毫秒，默认 3000）
     *
     * @return 连接超时毫秒数
     */
    int connectTimeout() default 3000;

    /**
     * 网络图片 HTTP 读取超时时间（单位毫秒，默认 10000）
     *
     * @return 读取超时毫秒数
     */
    int readTimeout() default 10000;
}
