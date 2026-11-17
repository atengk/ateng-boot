package io.github.atengk.boot.web.captcha.renderer;

import io.github.atengk.boot.web.captcha.properties.CaptchaProperties;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.Base64;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 纯 JDK 矢量 SVG 图形验证码渲染器单元测试
 *
 * @author Ateng
 * @since 2026-11-17
 */
@DisplayName("SvgCaptchaRenderer 单元测试")
class SvgCaptchaRendererTest {

    private final SvgCaptchaRenderer renderer = new SvgCaptchaRenderer();

    @Test
    @DisplayName("生成合法 Base64 SVG Data URL 且输出中绝不含明文 text 标签")
    void shouldRenderBase64SvgWithoutPlainTextTag() {
        CaptchaProperties properties = new CaptchaProperties();
        String dataUrl = renderer.render("A7b9", properties);

        assertThat(dataUrl).startsWith("data:image/svg+xml;base64,");

        String base64 = dataUrl.substring("data:image/svg+xml;base64,".length());
        String svg = new String(Base64.getDecoder().decode(base64), StandardCharsets.UTF_8);

        assertThat(svg).contains("<svg").contains("</svg>").contains("<path");
        assertThat(svg).doesNotContain("<text").doesNotContain("A7b9");
        assertThat(svg).contains("width=\"130\"").contains("height=\"44\"");
    }

    @Test
    @DisplayName("支持算术表达式字符集与自定义画布尺寸")
    void shouldRenderMathExpressionAndCustomDimensions() {
        CaptchaProperties properties = new CaptchaProperties();
        properties.setWidth(180);
        properties.setHeight(60);
        properties.setNoiseLineCount(6);
        properties.setNoiseDotCount(40);

        String dataUrl = renderer.render("8*9=?", properties);
        String svg = new String(
                Base64.getDecoder().decode(dataUrl.substring("data:image/svg+xml;base64,".length())),
                StandardCharsets.UTF_8
        );

        assertThat(svg).contains("width=\"180\"").contains("height=\"60\"").contains("<circle");
        assertThat(svg).doesNotContain("<text");
    }

    @Test
    @DisplayName("空文本或非法画布尺寸触发快速失败")
    void shouldFailFastOnInvalidInput() {
        CaptchaProperties properties = new CaptchaProperties();
        assertThatThrownBy(() -> renderer.render(" ", properties))
                .isInstanceOf(IllegalArgumentException.class);

        CaptchaProperties badProps = new CaptchaProperties();
        badProps.setWidth(0);
        assertThatThrownBy(() -> renderer.render("AB12", badProps))
                .isInstanceOf(IllegalArgumentException.class);

        assertThatThrownBy(() -> renderer.render("A$12", properties))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Unsupported captcha character");
    }
}
