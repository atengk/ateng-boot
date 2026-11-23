package io.github.atengk.boot.web.captcha.model;

/**
 * 图形验证码生成结果不可变契约模型
 * 与前端组件及统一接口响应直接对接，严禁包含标准答案明文
 *
 * @param captchaKey    本次生成的全局唯一验证码键
 * @param imageBase64   Base64 Data URL 格式图像载荷（如 {@code data:image/svg+xml;base64,...} 或 {@code data:image/png;base64,...}）
 * @param expireSeconds 验证码有效时长（秒）
 * @author Ateng
 * @since 2026-11-17
 */
public record CaptchaResult(
        String captchaKey,
        String imageBase64,
        long expireSeconds
) {
}
