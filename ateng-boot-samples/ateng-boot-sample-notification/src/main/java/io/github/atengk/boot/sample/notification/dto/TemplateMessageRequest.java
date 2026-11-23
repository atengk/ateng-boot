package io.github.atengk.boot.sample.notification.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

import java.io.Serial;
import java.io.Serializable;
import java.util.Map;

/**
 * 动态模板发信请求传输对象
 *
 * @author Ateng
 * @since 2026-11-10
 */
@Data
public class TemplateMessageRequest implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    /**
     * 发送目标渠道标识（可选，不传则回退默认渠道）
     */
    private String channel;

    /**
     * 模板内容（支持 ${var} 变量占位符）
     */
    @NotBlank(message = "模板内容不能为空")
    private String templateContent;

    /**
     * 模板插值变量字典
     */
    @NotNull(message = "模板变量字典不能为 null")
    private Map<String, Object> variables;
}
