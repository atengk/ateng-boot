package io.github.atengk.boot.excel.enums;

import org.apache.poi.ss.usermodel.IndexedColors;

import java.util.Locale;

/**
 * Excel 企业级预设多模版主题枚举
 * <p>
 * 封装表头背景色、正文基底色与斑马纹交替色等元数据，开箱即用支持多种企业级主流配色方案。
 * </p>
 *
 * @author Ateng
 * @since 2026-10-17
 */
public enum ExcelTheme {

    /**
     * 极简高雅白（默认推荐）：表头无底色加粗，正文纯白，保留精致细实线网格边框
     */
    MINIMAL_CLEAN(IndexedColors.WHITE.getIndex(), IndexedColors.WHITE.getIndex(), IndexedColors.WHITE.getIndex()),

    /**
     * 现代商务蓝：表头淡雅商务蓝，正文蓝白微光斑马纹交替
     */
    BUSINESS_BLUE(IndexedColors.PALE_BLUE.getIndex(), IndexedColors.WHITE.getIndex(), IndexedColors.LIGHT_TURQUOISE.getIndex()),

    /**
     * 经典沉稳灰（石墨灰 - 方案 B-2）：表头厚重石墨灰(#B0B7C0)，正文白底与清晰浅灰(#DEE2E6)斑马纹交替，黑色加粗字体，立体分明
     */
    CLASSIC_GREY(IndexedColors.GREY_25_PERCENT.getIndex(), IndexedColors.WHITE.getIndex(), IndexedColors.GREY_25_PERCENT.getIndex(),
            new byte[]{(byte) 0xB0, (byte) 0xB7, (byte) 0xC0}, new byte[]{(byte) 0xDE, (byte) 0xE2, (byte) 0xE6}),

    /**
     * 清新薄荷绿：表头浅绿底色，适合财务、资产或健康数据
     */
    FRESH_GREEN(IndexedColors.LIGHT_GREEN.getIndex(), IndexedColors.WHITE.getIndex(), IndexedColors.LEMON_CHIFFON.getIndex()),

    /**
     * 活力暖阳橙：表头浅橙底色，适合运营与电商数据
     */
    WARM_ORANGE(IndexedColors.LIGHT_ORANGE.getIndex(), IndexedColors.WHITE.getIndex(), IndexedColors.LEMON_CHIFFON.getIndex());

    private final short headerColorIndex;
    private final short contentBaseColorIndex;
    private final short zebraColorIndex;
    private final byte[] headerRgb;
    private final byte[] zebraRgb;

    ExcelTheme(short headerColorIndex, short contentBaseColorIndex, short zebraColorIndex) {
        this(headerColorIndex, contentBaseColorIndex, zebraColorIndex, null, null);
    }

    ExcelTheme(short headerColorIndex, short contentBaseColorIndex, short zebraColorIndex, byte[] headerRgb, byte[] zebraRgb) {
        this.headerColorIndex = headerColorIndex;
        this.contentBaseColorIndex = contentBaseColorIndex;
        this.zebraColorIndex = zebraColorIndex;
        this.headerRgb = headerRgb;
        this.zebraRgb = zebraRgb;
    }

    public short getHeaderColorIndex() {
        return headerColorIndex;
    }

    public short getContentBaseColorIndex() {
        return contentBaseColorIndex;
    }

    public short getZebraColorIndex() {
        return zebraColorIndex;
    }

    public byte[] getHeaderRgb() {
        return headerRgb;
    }

    public byte[] getZebraRgb() {
        return zebraRgb;
    }

    public boolean hasCustomRgb() {
        return headerRgb != null || zebraRgb != null;
    }

    /**
     * 根据主题标识符名称解析对应的主题枚举
     * 支持连字符与下划线不区分大小写，如 "minimal-clean"、"MINIMAL_CLEAN"、"classic-grey"
     *
     * @param themeName 主题名称
     * @return 匹配的 ExcelTheme 枚举，未匹配或为空时返回默认值 MINIMAL_CLEAN
     */
    public static ExcelTheme fromName(String themeName) {
        if (themeName == null || themeName.isBlank()) {
            return MINIMAL_CLEAN;
        }
        String normalized = themeName.trim().replace('-', '_').toUpperCase(Locale.ROOT);
        for (ExcelTheme theme : values()) {
            if (theme.name().equals(normalized)) {
                return theme;
            }
        }
        return MINIMAL_CLEAN;
    }
}
