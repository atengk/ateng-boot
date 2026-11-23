package io.github.atengk.boot.sample.ai.vo;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * AI 结构化用户画像分析结果视图对象
 *
 * @author Ateng
 * @since 2026-11-04
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class UserAnalysisVo {

    /**
     * 用户称谓或姓名
     */
    @NotBlank(message = "用户姓名不能为空")
    private String name;

    /**
     * 意向等级 (1-5 星)
     */
    @Min(value = 1, message = "意向等级最低为 1")
    @Max(value = 5, message = "意向等级最高为 5")
    private Integer intentLevel;

    /**
     * 提取的核心偏好标签
     */
    @NotEmpty(message = "用户核心偏好标签列表不能为空")
    private List<String> tags;

    /**
     * AI 总结的一句话建议
     */
    @NotBlank(message = "总结建议不能为空")
    private String summary;
}
