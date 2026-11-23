package io.github.atengk.boot.sample.ai.dto;

import jakarta.validation.constraints.NotBlank;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;

/**
 * 动态函数调用请求传输对象
 *
 * @author Ateng
 * @since 2026-11-05
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class AiDynamicToolRequest implements Serializable {

    private static final long serialVersionUID = 1L;

    /**
     * 用户问题 (如 "请帮我计算 125 乘以 8 是多少")
     */
    @NotBlank(message = "用户问题不能为空")
    private String question;
}
