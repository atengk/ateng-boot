package io.github.atengk.boot.document.pdf.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.Collections;
import java.util.Set;

/**
 * PDF 交互式表单（AcroForm）数据填充与防篡改治理配置选项
 *
 * @author Ateng
 * @since 2026-10-23
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class FormOptions {

    /**
     * 是否执行不可逆矢量扁平化（Flattening，将交互式控件彻底销毁并固化为静态矢量图形，默认: true）
     */
    @Builder.Default
    private boolean flatten = true;

    /**
     * 是否将填充后的所有表单字段设置为只读（默认: false）
     */
    @Builder.Default
    private boolean readOnly = false;

    /**
     * 指定需要设置为只读的特定字段名称集合（支持细粒度控制）
     */
    @Builder.Default
    private Set<String> readOnlyFields = Collections.emptySet();

    /**
     * 自定义中文字体路径（若填充内容含中文字符且模板缺失默认中文字体时使用）
     */
    private String customFontPath;

    /**
     * 快速构建默认不可逆矢量扁平化安全配置选项
     *
     * @return FormOptions 实例
     */
    public static FormOptions defaults() {
        return FormOptions.builder().build();
    }

    /**
     * 快速构建保留交互式表单（不扁平化）且全部字段置为只读的安全选项
     *
     * @return FormOptions 实例
     */
    public static FormOptions readOnlyInteractive() {
        return FormOptions.builder()
                .flatten(false)
                .readOnly(true)
                .build();
    }

    /**
     * 快速构建保留交互式表单且允许继续编辑的选项
     *
     * @return FormOptions 实例
     */
    public static FormOptions interactive() {
        return FormOptions.builder()
                .flatten(false)
                .readOnly(false)
                .build();
    }
}
