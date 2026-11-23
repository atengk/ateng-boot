package io.github.atengk.boot.sample.core.dto;

import jakarta.validation.constraints.NotBlank;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;

/**
 * 多维上下文跨线程透传请求对象
 *
 * @author Ateng
 * @since 2026-11-07
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class AsyncPropagateRequest implements Serializable {

    private static final long serialVersionUID = 1L;

    /**
     * 模拟用户 ID
     */
    @NotBlank(message = "用户ID不能为空")
    private String userId;

    /**
     * 模拟租户 ID
     */
    @NotBlank(message = "租户ID不能为空")
    private String tenantId;

    /**
     * 自定义扩展上下文测试数据
     */
    private String customContextData;
}
