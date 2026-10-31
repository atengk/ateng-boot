package io.github.atengk.boot.document.word.model;

import com.deepoove.poi.data.PictureRenderData;
import com.deepoove.poi.data.Pictures;
import lombok.Getter;
import org.openxmlformats.schemas.drawingml.x2006.wordprocessingDrawing.STRelFromH;
import org.openxmlformats.schemas.drawingml.x2006.wordprocessingDrawing.STRelFromV;

import java.io.File;
import java.io.InputStream;
import java.util.Objects;

/**
 * 浮动图层电子印章与手写签名参数持有模型
 *
 * @author Ateng
 * @since 2026-10-23
 */
@Getter
public class FloatingPicture {

    /**
     * 底层 poi-tl 图片渲染数据
     */
    private final PictureRenderData pictureData;

    /**
     * 水平 X 轴微调偏移量（单位：像素 px/pt）
     */
    private final int dx;

    /**
     * 垂直 Y 轴微调偏移量（单位：像素 px/pt，负数表示向上偏移覆盖在落款文字上方）
     */
    private final int dy;

    /**
     * 是否浮于文字上方（true: 浮于上方 behindDoc=0；false: 衬于文字下方 behindDoc=1）
     */
    private final boolean aboveText;

    /**
     * 水平相对锚定基准（默认 STRelFromH.CHARACTER，相对于字符/文本 Run 锚定）
     */
    private final STRelFromH.Enum relativeFromH;

    /**
     * 垂直相对锚定基准（默认 STRelFromV.PARAGRAPH，相对于段落锚定）
     */
    private final STRelFromV.Enum relativeFromV;

    public FloatingPicture(PictureRenderData pictureData, int dx, int dy, boolean aboveText) {
        this(pictureData, dx, dy, aboveText, STRelFromH.CHARACTER, STRelFromV.PARAGRAPH);
    }

    public FloatingPicture(PictureRenderData pictureData, int dx, int dy, boolean aboveText,
                           STRelFromH.Enum relativeFromH, STRelFromV.Enum relativeFromV) {
        this.pictureData = Objects.requireNonNull(pictureData, "pictureData 不能为空");
        this.dx = dx;
        this.dy = dy;
        this.aboveText = aboveText;
        this.relativeFromH = relativeFromH != null ? relativeFromH : STRelFromH.CHARACTER;
        this.relativeFromV = relativeFromV != null ? relativeFromV : STRelFromV.PARAGRAPH;
    }

    /**
     * 基于已有 PictureRenderData 创建默认浮动印章（位于文字上方，偏移为 0）
     *
     * @param pictureData 图片渲染数据
     * @return FloatingPicture 实例
     */
    public static FloatingPicture of(PictureRenderData pictureData) {
        return new FloatingPicture(pictureData, 0, 0, true);
    }

    /**
     * 基于已有 PictureRenderData 创建带坐标偏移的浮动印章
     *
     * @param pictureData 图片渲染数据
     * @param dx          X 偏移
     * @param dy          Y 偏移
     * @return FloatingPicture 实例
     */
    public static FloatingPicture of(PictureRenderData pictureData, int dx, int dy) {
        return new FloatingPicture(pictureData, dx, dy, true);
    }

    /**
     * 基于已有 PictureRenderData 创建带坐标偏移与自定义锚定基准的浮动印章
     *
     * @param pictureData   图片渲染数据
     * @param dx            X 偏移
     * @param dy            Y 偏移
     * @param relativeFromH 水平锚定基准
     * @param relativeFromV 垂直锚定基准
     * @return FloatingPicture 实例
     */
    public static FloatingPicture of(PictureRenderData pictureData, int dx, int dy,
                                     STRelFromH.Enum relativeFromH, STRelFromV.Enum relativeFromV) {
        return new FloatingPicture(pictureData, dx, dy, true, relativeFromH, relativeFromV);
    }

    /**
     * 从二进制字节数组构建浮动印章
     *
     * @param bytes  图片字节
     * @param width  显示宽度
     * @param height 显示高度
     * @return FloatingPicture 实例
     */
    public static FloatingPicture ofBytes(byte[] bytes, int width, int height) {
        return of(Pictures.ofBytes(bytes).size(width, height).create());
    }

    /**
     * 从二进制字节数组构建带指定坐标偏移的浮动印章
     *
     * @param bytes  图片字节
     * @param width  显示宽度
     * @param height 显示高度
     * @param dx     X 偏移
     * @param dy     Y 偏移
     * @return FloatingPicture 实例
     */
    public static FloatingPicture ofBytes(byte[] bytes, int width, int height, int dx, int dy) {
        return new FloatingPicture(Pictures.ofBytes(bytes).size(width, height).create(), dx, dy, true);
    }

    /**
     * 从二进制字节数组构建带指定坐标偏移与自定义锚定基准的浮动印章
     *
     * @param bytes         图片字节
     * @param width         显示宽度
     * @param height        显示高度
     * @param dx            X 偏移
     * @param dy            Y 偏移
     * @param relativeFromH 水平锚定基准
     * @param relativeFromV 垂直锚定基准
     * @return FloatingPicture 实例
     */
    public static FloatingPicture ofBytes(byte[] bytes, int width, int height, int dx, int dy,
                                          STRelFromH.Enum relativeFromH, STRelFromV.Enum relativeFromV) {
        return new FloatingPicture(Pictures.ofBytes(bytes).size(width, height).create(), dx, dy, true, relativeFromH, relativeFromV);
    }

    /**
     * 从本地物理文件构建带指定坐标偏移的浮动印章
     *
     * @param file   本地图片文件
     * @param width  显示宽度
     * @param height 显示高度
     * @param dx     X 偏移
     * @param dy     Y 偏移
     * @return FloatingPicture 实例
     */
    public static FloatingPicture ofFile(File file, int width, int height, int dx, int dy) {
        return new FloatingPicture(Pictures.ofLocal(file.getAbsolutePath()).size(width, height).create(), dx, dy, true);
    }

    /**
     * 从本地物理文件构建带指定坐标偏移与自定义锚定基准的浮动印章
     *
     * @param file          本地图片文件
     * @param width         显示宽度
     * @param height        显示高度
     * @param dx            X 偏移
     * @param dy            Y 偏移
     * @param relativeFromH 水平锚定基准
     * @param relativeFromV 垂直锚定基准
     * @return FloatingPicture 实例
     */
    public static FloatingPicture ofFile(File file, int width, int height, int dx, int dy,
                                         STRelFromH.Enum relativeFromH, STRelFromV.Enum relativeFromV) {
        return new FloatingPicture(Pictures.ofLocal(file.getAbsolutePath()).size(width, height).create(), dx, dy, true, relativeFromH, relativeFromV);
    }

    /**
     * 从输入流构建带指定坐标偏移的浮动印章
     *
     * @param stream 图片输入流
     * @param width  显示宽度
     * @param height 显示高度
     * @param dx     X 偏移
     * @param dy     Y 偏移
     * @return FloatingPicture 实例
     */
    public static FloatingPicture ofStream(InputStream stream, int width, int height, int dx, int dy) {
        return new FloatingPicture(Pictures.ofStream(stream).size(width, height).create(), dx, dy, true);
    }

    /**
     * 从输入流构建带指定坐标偏移与自定义锚定基准的浮动印章
     *
     * @param stream        图片输入流
     * @param width         显示宽度
     * @param height        显示高度
     * @param dx            X 偏移
     * @param dy            Y 偏移
     * @param relativeFromH 水平锚定基准
     * @param relativeFromV 垂直锚定基准
     * @return FloatingPicture 实例
     */
    public static FloatingPicture ofStream(InputStream stream, int width, int height, int dx, int dy,
                                           STRelFromH.Enum relativeFromH, STRelFromV.Enum relativeFromV) {
        return new FloatingPicture(Pictures.ofStream(stream).size(width, height).create(), dx, dy, true, relativeFromH, relativeFromV);
    }
}
