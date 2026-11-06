package io.github.atengk.boot.sample.ai.dto;

import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;
import java.util.Map;

/**
 * 情景记忆事实手动记忆请求传输契约
 *
 * @author Ateng
 * @since 2026-11-06
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class MemoryFactRememberRequest implements Serializable {

    private static final long serialVersionUID = 1L;

    /**
     * 事实主体 (Subject)
     */
    @NotBlank(message = "事实主体 subject 不能为空")
    private String subject;

    /**
     * 事实谓词/属性键 (Predicate)
     */
    @NotBlank(message = "事实谓词 predicate 不能为空")
    private String predicate;

    /**
     * 事实客体/值 (Object)
     */
    @NotBlank(message = "事实客体 object 不能为空")
    private String object;

    /**
     * 完整自然语言事实陈述句 (可选，若为空自动由三元组拼装)
     */
    private String statement;

    /**
     * 事实置信度分数 (0.0 ~ 1.0)
     */
    @DecimalMin(value = "0.0", message = "置信度不能小于 0.0")
    @DecimalMax(value = "1.0", message = "置信度不能大于 1.0")
    private Double confidence;

    /**
     * 归属用户标识 (可选，缺省从上下文读取)
     */
    private String userId;

    /**
     * 归属租户标识 (可选，缺省从上下文读取)
     */
    private String tenantId;

    /**
     * 扩展元数据
     */
    private Map<String, Object> metadata;
}
