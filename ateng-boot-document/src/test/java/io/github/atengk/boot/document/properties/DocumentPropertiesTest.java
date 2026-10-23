package io.github.atengk.boot.document.properties;

import io.github.atengk.boot.document.enums.WatermarkLayer;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.util.unit.DataSize;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 文档治理配置属性单元测试
 *
 * @author Ateng
 * @since 2026-10-23
 */
@DisplayName("文档治理配置属性单元测试")
class DocumentPropertiesTest {

    @Test
    @DisplayName("测试默认配置属性自洽性")
    void testDefaultProperties() {
        DocumentProperties properties = new DocumentProperties();

        assertThat(properties.isEnabled()).isTrue();
        assertThat(properties.getInMemoryThreshold()).isEqualTo(DataSize.ofMegabytes(10));
        assertThat(properties.getTempDir()).isEmpty();
        assertThat(properties.resolveEffectiveTempDir()).contains("ateng-boot-document");

        // Word 属性
        assertThat(properties.getWord()).isNotNull();
        assertThat(properties.getWord().getTemplateBaseDir()).isEqualTo("classpath:/templates/word/");

        // PDF 属性
        assertThat(properties.getPdf()).isNotNull();
        assertThat(properties.getPdf().getFontPath()).isEmpty();
        assertThat(properties.getPdf().getPreferredFontNames())
                .containsExactly("SimSun", "Microsoft YaHei", "Source Han Sans CN", "WenQuanYi Micro Hei", "PingFang SC");

        // 水印属性
        assertThat(properties.getWatermark()).isNotNull();
        assertThat(properties.getWatermark().getDefaultText()).isEqualTo("#{user.nickname!''} #{now('yyyy-MM-dd')}");
        assertThat(properties.getWatermark().getOpacity()).isEqualTo(0.2f);
        assertThat(properties.getWatermark().getRotation()).isEqualTo(-30.0f);
        assertThat(properties.getWatermark().getFontSize()).isEqualTo(24.0f);
        assertThat(properties.getWatermark().getColor()).isEqualTo("#888888");
        assertThat(properties.getWatermark().getLayer()).isEqualTo(WatermarkLayer.OVERLAY);
        assertThat(properties.getWatermark().getRowSpacing()).isEqualTo(150.0f);
        assertThat(properties.getWatermark().getColSpacing()).isEqualTo(200.0f);
    }

    @Test
    @DisplayName("测试自定义配置属性设值与临时目录解析")
    void testCustomProperties() {
        DocumentProperties properties = new DocumentProperties();
        properties.setEnabled(false);
        properties.setInMemoryThreshold(DataSize.ofMegabytes(50));
        properties.setTempDir("/data/custom/tmp");

        assertThat(properties.isEnabled()).isFalse();
        assertThat(properties.getInMemoryThreshold()).isEqualTo(DataSize.ofMegabytes(50));
        assertThat(properties.resolveEffectiveTempDir()).isEqualTo("/data/custom/tmp");

        properties.getWord().setTemplateBaseDir("file:/opt/templates/");
        assertThat(properties.getWord().getTemplateBaseDir()).isEqualTo("file:/opt/templates/");

        properties.getPdf().setFontPath("/fonts/custom.ttf");
        assertThat(properties.getPdf().getFontPath()).isEqualTo("/fonts/custom.ttf");

        properties.getWatermark().setLayer(WatermarkLayer.UNDERLAY);
        properties.getWatermark().setColor("#000000");
        properties.getWatermark().setOpacity(0.5f);
        assertThat(properties.getWatermark().getLayer()).isEqualTo(WatermarkLayer.UNDERLAY);
        assertThat(properties.getWatermark().getColor()).isEqualTo("#000000");
        assertThat(properties.getWatermark().getOpacity()).isEqualTo(0.5f);
    }
}
