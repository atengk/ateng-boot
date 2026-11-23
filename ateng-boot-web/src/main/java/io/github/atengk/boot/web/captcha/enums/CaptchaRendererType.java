package io.github.atengk.boot.web.captcha.enums;

/**
 * 图形验证码内置图像渲染引擎类型枚举
 *
 * @author Ateng
 * @since 2026-11-17
 */
public enum CaptchaRendererType {

    /**
     * 零系统字体依赖的矢量 SVG 渲染引擎（默认）
     */
    SVG,

    /**
     * 基于 JDK BufferedImage / Graphics2D 的 PNG 位图渲染引擎
     */
    PNG
}
