package io.github.atengk.boot.web.captcha.renderer;

import io.github.atengk.boot.web.captcha.properties.CaptchaProperties;

/**
 * 图形验证码图像渲染 SPI 接口
 * 负责将展示文本按配置参数渲染为 Base64 Data URL 图像载荷
 *
 * @author Ateng
 * @since 2026-11-17
 */
public interface CaptchaRenderer {

    /**
     * 渲染验证码图像并返回 Base64 Data URL
     *
     * @param displayText 需在图像中展示的文本内容（如随机字符或算术表达式）
     * @param properties  验证码运行时配置属性
     * @return 标准 Base64 Data URL（如 {@code data:image/svg+xml;base64,...} 或 {@code data:image/png;base64,...}）
     */
    String render(String displayText, CaptchaProperties properties);
}
