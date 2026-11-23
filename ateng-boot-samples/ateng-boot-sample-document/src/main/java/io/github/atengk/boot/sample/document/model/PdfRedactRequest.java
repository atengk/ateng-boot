package io.github.atengk.boot.sample.document.model;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * PDF 敏感内容物理不可逆涂黑脱敏请求传输对象
 *
 * @author Ateng
 * @since 2026-11-18
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class PdfRedactRequest {

    /**
     * 待物理涂黑抹除的敏感关键字列表
     */
    @NotEmpty(message = "待涂黑关键字列表不能为空")
    private List<@NotBlank(message = "涂黑关键字元素不能为空") String> keywords;

    /**
     * 涂黑遮罩十六进制填充颜色（默认 #000000）
     */
    @Builder.Default
    private String maskColor = "#000000";

    /**
     * 光栅重封 DPI（可选，默认使用服务端 redactionDpi 配置）
     */
    private Float dpi;

    /**
     * 源 PDF 打开密码（可选）
     */
    private String password;
}
