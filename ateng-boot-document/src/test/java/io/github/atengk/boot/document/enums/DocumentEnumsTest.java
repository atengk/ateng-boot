package io.github.atengk.boot.document.enums;

import io.github.atengk.boot.core.enums.IBaseEnum;
import io.github.atengk.boot.core.util.EnumUtil;
import io.github.atengk.boot.document.font.FontResolutionTier;
import io.github.atengk.boot.document.pdf.model.PdfFormFieldDescriptor;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 文档治理模块通用业务枚举契约单元测试
 *
 * @author Ateng
 * @since 2026-11-18
 */
@DisplayName("文档治理模块通用业务枚举契约单元测试")
class DocumentEnumsTest {

    @Test
    @DisplayName("测试全部文档业务枚举统一实现 IBaseEnum<String> 并支持 EnumUtil 解析")
    void testAllDocumentEnumsImplementIBaseEnum() {
        assertThat(WatermarkLayer.OVERLAY).isInstanceOf(IBaseEnum.class);
        assertThat(WatermarkLayer.OVERLAY.getCode()).isEqualTo("overlay");
        assertThat(WatermarkLayer.OVERLAY.getLabel()).isEqualTo("前置覆盖");
        assertThat(WatermarkLayer.OVERLAY.getValue()).isEqualTo("overlay");
        assertThat(EnumUtil.fromCode(WatermarkLayer.class, "underlay")).contains(WatermarkLayer.UNDERLAY);

        assertThat(PageNumberPosition.BOTTOM_CENTER).isInstanceOf(IBaseEnum.class);
        assertThat(PageNumberPosition.BOTTOM_CENTER.getCode()).isEqualTo("bottom-center");
        assertThat(PageNumberPosition.BOTTOM_CENTER.getLabel()).isEqualTo("底部居中");
        assertThat(EnumUtil.fromCode(PageNumberPosition.class, "top-right")).contains(PageNumberPosition.TOP_RIGHT);

        assertThat(CrossPageSealEdge.RIGHT).isInstanceOf(IBaseEnum.class);
        assertThat(CrossPageSealEdge.RIGHT.getCode()).isEqualTo("right");
        assertThat(CrossPageSealEdge.RIGHT.getLabel()).isEqualTo("右侧边缘");
        assertThat(EnumUtil.fromCode(CrossPageSealEdge.class, "left")).contains(CrossPageSealEdge.LEFT);

        assertThat(FontResolutionTier.CONFIG).isInstanceOf(IBaseEnum.class);
        assertThat(FontResolutionTier.CONFIG.getCode()).isEqualTo("config");
        assertThat(FontResolutionTier.CONFIG.getLabel()).isNotBlank();
        assertThat(EnumUtil.fromCode(FontResolutionTier.class, "system")).contains(FontResolutionTier.SYSTEM);

        assertThat(PdfFormFieldDescriptor.FieldCategory.TEXT).isInstanceOf(IBaseEnum.class);
        assertThat(PdfFormFieldDescriptor.FieldCategory.TEXT.getCode()).isEqualTo("text");
        assertThat(PdfFormFieldDescriptor.FieldCategory.TEXT.getLabel()).isNotBlank();
        assertThat(EnumUtil.fromCode(PdfFormFieldDescriptor.FieldCategory.class, "checkbox"))
                .contains(PdfFormFieldDescriptor.FieldCategory.CHECKBOX);

        assertThat(KeywordStampOccurrence.LAST).isInstanceOf(IBaseEnum.class);
        assertThat(KeywordStampOccurrence.FIRST.getCode()).isEqualTo("first");
        assertThat(KeywordStampOccurrence.LAST.getCode()).isEqualTo("last");
        assertThat(KeywordStampOccurrence.ALL.getCode()).isEqualTo("all");
        assertThat(EnumUtil.getByCodeOrThrow(KeywordStampOccurrence.class, "last"))
                .isEqualTo(KeywordStampOccurrence.LAST);
    }
}
