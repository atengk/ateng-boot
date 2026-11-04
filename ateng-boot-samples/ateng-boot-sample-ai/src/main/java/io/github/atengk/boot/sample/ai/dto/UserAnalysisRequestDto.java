package io.github.atengk.boot.sample.ai.dto;

import jakarta.validation.constraints.NotBlank;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 结构化用户画像抽取请求 DTO
 *
 * @author Ateng
 * @since 2026-11-04
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class UserAnalysisRequestDto {

    /**
     * 用户姓名或代号
     */
    @NotBlank(message = "用户姓名或代号不能为空")
    private String userName;

    /**
     * 用户的咨询或行为文本描述
     */
    @NotBlank(message = "行为描述文本不能为空")
    private String content;

    /**
     * 可选指定模型名称
     */
    private String model;
}
