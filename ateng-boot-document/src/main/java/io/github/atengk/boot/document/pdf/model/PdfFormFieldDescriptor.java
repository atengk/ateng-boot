package io.github.atengk.boot.document.pdf.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * AcroForm 交互式表单字段元数据描述模型
 *
 * @author Ateng
 * @since 2026-10-31
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class PdfFormFieldDescriptor {

    /**
     * 字段短标识名（Partial Name，如 "supplierName"）
     */
    private String name;

    /**
     * 字段完整层级名（Fully Qualified Name，如 "form[0].supplierName"）
     */
    private String fullyQualifiedName;

    /**
     * PDFBox 底层原始字段类型标识（如 "Tx", "Btn", "Ch", "Sig"）
     */
    private String fieldType;

    /**
     * 表单域语义化分类枚举
     */
    private FieldCategory fieldCategory;

    /**
     * 字段当前值（若无值则为空字符串或 null）
     */
    private String value;

    /**
     * 是否为只读锁定状态
     */
    private boolean readOnly;

    /**
     * 是否为必填项
     */
    private boolean required;

    /**
     * 表单字段语义化分类枚举
     */
    public enum FieldCategory {
        /**
         * 文本输入域（PDTextField）
         */
        TEXT,

        /**
         * 复选框（PDCheckBox）
         */
        CHECKBOX,

        /**
         * 单选按钮组（PDRadioButton）
         */
        RADIO_BUTTON,

        /**
         * 选择列表或下拉框（PDChoice: ListBox / ComboBox）
         */
        CHOICE,

        /**
         * 数字签名域（PDSignatureField）
         */
        SIGNATURE,

        /**
         * 其他或未知类型字段
         */
        OTHER
    }
}
