package io.github.atengk.boot.excel.properties;

import lombok.Data;

/**
 * Excel 样式与主题配置属性
 *
 * @author Ateng
 * @since 2026-10-17
 */
@Data
public class ExcelStyleProperties {

    /**
     * 是否启用企业级优雅样式体系（设为 false 则完全还原为 Fesod 原生裸输出）
     */
    private boolean enabled = true;

    /**
     * 全局默认预设模版主题名称
     * 可选：minimal-clean, business-blue, classic-grey, fresh-green, warm-orange
     */
    private String theme = "minimal-clean";

    /**
     * 是否开启正文奇偶行斑马纹交替浅底纹
     */
    private boolean zebraStripe = true;

    /**
     * 是否开启中英文智能自适应列宽
     */
    private boolean autoColumnWidth = true;

    /**
     * 智能自适应列宽的最小列宽（字符数）
     */
    private int minColumnWidth = 12;

    /**
     * 智能自适应列宽的最大列宽（字符数）
     */
    private int maxColumnWidth = 60;

    /**
     * 智能自适应列宽的内边距补偿（字符数，预留左右安全间隙，杜绝数值变 ###）
     */
    private int paddingColumnWidth = 2;

    /**
     * 默认全局字体名称
     */
    private String fontName = "微软雅黑";
}
