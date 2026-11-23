package io.github.atengk.boot.document.pdf.model;

import io.github.atengk.boot.document.enums.KeywordStampOccurrence;
import io.github.atengk.boot.document.enums.WatermarkLayer;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.Set;

/**
 * PDF 基于关键字自动定位压印电子公章配置选项
 *
 * @author Ateng
 * @since 2026-11-18
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class KeywordStampOptions {

    /**
     * 待定位锚点关键字文本（如 "甲方（盖章）"）
     */
    private String keyword;

    /**
     * 电子公章或手写签名图片输入源（支持 byte[], InputStream, File, Path, Resource, BufferedImage, String）
     */
    private Object stampImage;

    /**
     * 印章目标绘制宽度（单位：pt，默认 100.0f）
     */
    @Builder.Default
    private float width = 100.0f;

    /**
     * 印章目标绘制高度（单位：pt，默认 100.0f）
     */
    @Builder.Default
    private float height = 100.0f;

    /**
     * 相对于关键字左下角基准的 X 轴水平微调偏移量（单位：pt，默认 0.0f）
     */
    @Builder.Default
    private float offsetX = 0.0f;

    /**
     * 相对于关键字左下角基准的 Y 轴垂直微调偏移量（单位：pt，默认 0.0f）
     */
    @Builder.Default
    private float offsetY = 0.0f;

    /**
     * 印章不透明度（0.0f ~ 1.0f，默认 1.0f）
     */
    @Builder.Default
    private float opacity = 1.0f;

    /**
     * 盖章图层放置模式（默认 OVERLAY 覆盖在文字上方）
     */
    @Builder.Default
    private WatermarkLayer layer = WatermarkLayer.OVERLAY;

    /**
     * 当多处命中同一关键字时的盖章匹配策略（FIRST / LAST / ALL，默认 LAST）
     */
    @Builder.Default
    private KeywordStampOccurrence occurrence = KeywordStampOccurrence.LAST;

    /**
     * 限定搜索与盖章的 1-based 目标页码集合（为空时扫描全部页面）
     */
    private Set<Integer> targetPages;

    /**
     * 未命中任何关键字时是否快速失败抛出 {@code KEYWORD_NOT_FOUND} 异常（默认 true）
     */
    @Builder.Default
    private boolean failIfNotFound = true;

    /**
     * 打开加密 PDF 文档的密码（可选）
     */
    private String password;
}
