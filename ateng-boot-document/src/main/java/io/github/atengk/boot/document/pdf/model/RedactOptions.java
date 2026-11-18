package io.github.atengk.boot.document.pdf.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * PDF 敏感内容物理不可逆涂黑脱敏（Redaction）配置选项
 *
 * @author Ateng
 * @since 2026-11-18
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class RedactOptions {

    /**
     * 需要全文匹配并物理涂黑的敏感关键字列表
     */
    @Builder.Default
    private List<String> keywords = new ArrayList<>();

    /**
     * 需要按物理坐标区域物理涂黑的矩形清单
     */
    @Builder.Default
    private List<RedactArea> areas = new ArrayList<>();

    /**
     * 涂黑遮罩十六进制填充颜色（默认纯黑 #000000）
     */
    @Builder.Default
    private String maskColor = "#000000";

    /**
     * 涂黑遮罩十六进制填充颜色别名（非空时优先于 maskColor，兼容不同调用风格）
     */
    private String color;

    /**
     * 关键字命中包围盒四周外扩安全边距（单位：pt，默认 2.0f）
     */
    @Builder.Default
    private float padding = 2.0f;

    /**
     * 命中页光栅化重封 DPI（为 null 时回退全局配置 redactionDpi，默认 200.0f）
     */
    private Float dpi;

    /**
     * 限定脱敏处理的 1-based 目标页码集合（为空时扫描全部页面）
     */
    private Set<Integer> targetPages;

    /**
     * 打开加密 PDF 文档的密码（可选）
     */
    private String password;

    /**
     * 当指定关键字或目标区域在文档中零命中时是否快速失败抛出 KEYWORD_NOT_FOUND（默认 false 保持兼容）
     */
    @Builder.Default
    private boolean failIfNotFound = false;

    /**
     * 关键字检索匹配时是否忽略英文字母大小写（默认 false）
     */
    @Builder.Default
    private boolean ignoreCase = false;

    /**
     * 解析实际生效的遮罩十六进制颜色
     *
     * @return 非空遮罩颜色值
     */
    public String resolveEffectiveMaskColor() {
        return (color != null && !color.isBlank()) ? color : maskColor;
    }
}
