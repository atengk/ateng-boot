package io.github.atengk.boot.document.pdf.model;

import io.github.atengk.boot.document.enums.CrossPageSealEdge;
import io.github.atengk.boot.document.enums.WatermarkLayer;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;
import java.util.Objects;

/**
 * PDF 多页合同跨页骑缝章配置选项
 * <p>
 * 用于在多页 PDF 合同（或指定页面区间）中按页自动垂直/水平等分切片印章，
 * 并以保留 Alpha 透明通道的光栅无损贴合压印在各页边缘，拼接即可还原完整公章。
 * </p>
 *
 * @author Ateng
 * @since 2026-10-31
 */
@Data
@Builder(toBuilder = true)
@NoArgsConstructor
@AllArgsConstructor
public class CrossPageSealOptions {

    /**
     * 校验印章尺寸合法性（宽度和高度必须大于 0）
     *
     * @param width  印章宽度
     * @param height 印章高度
     * @throws IllegalArgumentException 当尺寸非正数时抛出
     */
    public static void validateDimensions(float width, float height) {
        if (width <= 0.0f || height <= 0.0f) {
            throw new IllegalArgumentException(String.format("印章尺寸必须大于 0 (width: %.1f, height: %.1f)", width, height));
        }
    }

    /**
     * 默认印章总宽度（单位：pt）
     */
    public static final float DEFAULT_SEAL_WIDTH = 120.0f;

    /**
     * 默认印章总高度（单位：pt）
     */
    public static final float DEFAULT_SEAL_HEIGHT = 120.0f;

    /**
     * 印章图片输入源（支持 byte[], InputStream, File, Path, Resource, BufferedImage, String 路径）
     */
    private Object sealImage;

    /**
     * 印章总宽度（单位：pt，默认 120.0f）
     */
    @Builder.Default
    private float width = DEFAULT_SEAL_WIDTH;

    /**
     * 印章总高度（单位：pt，默认 120.0f）
     */
    @Builder.Default
    private float height = DEFAULT_SEAL_HEIGHT;

    /**
     * 骑缝章贴合边缘（默认 RIGHT 右侧边缘居中）
     */
    @Builder.Default
    private CrossPageSealEdge edge = CrossPageSealEdge.RIGHT;

    /**
     * 距贴合边缘的物理间距偏移（单位：pt，默认 0.0f 贴边）
     */
    @Builder.Default
    private float margin = 0.0f;

    /**
     * 垂直或水平居中偏移量（单位：pt，默认 0.0f 正居中）
     * <ul>
     *     <li>当 edge 为 RIGHT / LEFT 时：表示 Y 轴垂直偏移（正数向上，负数向下）</li>
     *     <li>当 edge 为 TOP / BOTTOM 时：表示 X 轴水平偏移（正数向右，负数向左）</li>
     * </ul>
     */
    @Builder.Default
    private float offset = 0.0f;

    /**
     * 是否跳过首页/封面页（默认 false；为 true 时从第 2 页开始切片并盖骑缝章）
     */
    @Builder.Default
    private boolean skipCover = false;

    /**
     * 是否跳过末页/封底页（默认 false；为 true 时不盖骑缝章至最后一页）
     */
    @Builder.Default
    private boolean skipBackCover = false;

    /**
     * 自定义目标盖章页面索引列表（从 0 开始；支持负数，-1 为最后一页；若非空则优先使用此列表，忽略 skipCover / skipBackCover）
     */
    private List<Integer> targetPages;

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
     * 静态工厂：使用指定印章图片创建默认骑缝章选项（120pt * 120pt，右侧边缘居中贴边）
     *
     * @param sealImage 印章图片输入源
     * @return CrossPageSealOptions 实例
     */
    public static CrossPageSealOptions of(Object sealImage) {
        Objects.requireNonNull(sealImage, "印章图片输入源 sealImage 不能为空");
        return CrossPageSealOptions.builder()
                .sealImage(sealImage)
                .build();
    }

    /**
     * 静态工厂：使用指定印章图片与宽高创建骑缝章选项
     *
     * @param sealImage 印章图片输入源
     * @param width     印章宽度（pt）
     * @param height    印章高度（pt）
     * @return CrossPageSealOptions 实例
     */
    public static CrossPageSealOptions of(Object sealImage, float width, float height) {
        Objects.requireNonNull(sealImage, "印章图片输入源 sealImage 不能为空");
        validateDimensions(width, height);
        return CrossPageSealOptions.builder()
                .sealImage(sealImage)
                .width(width)
                .height(height)
                .build();
    }

    /**
     * 静态工厂：使用指定印章图片与贴合边缘创建骑缝章选项
     *
     * @param sealImage 印章图片输入源
     * @param edge      骑缝边缘位置
     * @return CrossPageSealOptions 实例
     */
    public static CrossPageSealOptions of(Object sealImage, CrossPageSealEdge edge) {
        Objects.requireNonNull(sealImage, "印章图片输入源 sealImage 不能为空");
        return CrossPageSealOptions.builder()
                .sealImage(sealImage)
                .edge(edge)
                .build();
    }
}
