package io.github.atengk.boot.document.pdf.model;

import io.github.atengk.boot.document.enums.WatermarkLayer;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.Objects;

/**
 * PDF 电子公章与手写签名绝对物理坐标压印选项
 *
 * @author Ateng
 * @since 2026-10-23
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ImageStampOptions {

    /**
     * 印章图片输入源（支持 byte[], InputStream, File, Path, Resource, String 路径）
     */
    private Object image;

    /**
     * 目标压印页面索引（从 0 开始；支持负数，-1 表示最后一页，默认 -1）
     */
    @Builder.Default
    private int pageIndex = -1;

    /**
     * 压印区域左下角绝对物理 X 轴坐标（单位：pt，从页面左侧边界算起）
     */
    @Builder.Default
    private float x = 0.0f;

    /**
     * 压印区域左下角绝对物理 Y 轴坐标（单位：pt，从页面底部边界算起）
     */
    @Builder.Default
    private float y = 0.0f;

    /**
     * 印章压印宽度（单位：pt）
     */
    private float width;

    /**
     * 印章压印高度（单位：pt）
     */
    private float height;

    /**
     * 印章不透明度（0.0f 为完全透明，1.0f 为完全实心，默认 1.0f）
     */
    @Builder.Default
    private float opacity = 1.0f;

    /**
     * 印章图层放置模式（OVERLAY: 前置覆盖 / UNDERLAY: 背景底层，默认 OVERLAY）
     */
    @Builder.Default
    private WatermarkLayer layer = WatermarkLayer.OVERLAY;

    /**
     * 静态工厂：创建指定页面和坐标的电子印章压印选项
     *
     * @param image     印章图片数据源
     * @param pageIndex 页面索引（-1 为最后一页）
     * @param x         左下角 X 坐标
     * @param y         左下角 Y 坐标
     * @param width     宽度
     * @param height    高度
     * @return ImageStampOptions 实例
     */
    public static ImageStampOptions of(Object image, int pageIndex, float x, float y, float width, float height) {
        Objects.requireNonNull(image, "印章图片输入源 image 不能为空");
        return ImageStampOptions.builder()
                .image(image)
                .pageIndex(pageIndex)
                .x(x)
                .y(y)
                .width(width)
                .height(height)
                .build();
    }
}
