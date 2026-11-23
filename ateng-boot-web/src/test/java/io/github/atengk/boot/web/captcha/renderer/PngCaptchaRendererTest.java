package io.github.atengk.boot.web.captcha.renderer;

import io.github.atengk.boot.web.captcha.properties.CaptchaProperties;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.util.Base64;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 纯 JDK BufferedImage PNG 图形验证码渲染器单元测试
 *
 * @author Ateng
 * @since 2026-11-17
 */
@DisplayName("PngCaptchaRenderer 单元测试")
class PngCaptchaRendererTest {

    private final PngCaptchaRenderer renderer = new PngCaptchaRenderer();

    @Test
    @DisplayName("生成合法 Base64 PNG Data URL 且可被 ImageIO 完整解码")
    void shouldRenderValidPngDataUrl() throws Exception {
        CaptchaProperties properties = new CaptchaProperties();
        properties.setWidth(140);
        properties.setHeight(48);

        String dataUrl = renderer.render("3+5=?", properties);

        assertThat(dataUrl).startsWith("data:image/png;base64,");
        byte[] bytes = Base64.getDecoder().decode(dataUrl.substring("data:image/png;base64,".length()));
        BufferedImage image = ImageIO.read(new ByteArrayInputStream(bytes));
        assertThat(image).isNotNull();
        assertThat(image.getWidth()).isEqualTo(140);
        assertThat(image.getHeight()).isEqualTo(48);
    }

    @Test
    @DisplayName("空文本或非法高度触发快速失败")
    void shouldFailFastOnInvalidInput() {
        CaptchaProperties properties = new CaptchaProperties();
        assertThatThrownBy(() -> renderer.render("", properties))
                .isInstanceOf(IllegalArgumentException.class);

        CaptchaProperties badProps = new CaptchaProperties();
        badProps.setHeight(-10);
        assertThatThrownBy(() -> renderer.render("XY23", badProps))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("长文本在窄画布下按单字格宽自适应缩放且正常解码")
    void shouldScaleFontSizeForLongTextOnNarrowCanvas() throws Exception {
        CaptchaProperties narrowProps = new CaptchaProperties();
        narrowProps.setWidth(120);
        narrowProps.setHeight(44);

        String dataUrl = renderer.render("ABCDEFGHIJKLMNOP", narrowProps);
        byte[] bytes = Base64.getDecoder().decode(dataUrl.substring("data:image/png;base64,".length()));
        BufferedImage image = ImageIO.read(new ByteArrayInputStream(bytes));
        assertThat(image).isNotNull();
        assertThat(image.getWidth()).isEqualTo(120);
    }
}
