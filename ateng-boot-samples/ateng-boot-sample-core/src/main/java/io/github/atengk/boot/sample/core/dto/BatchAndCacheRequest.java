package io.github.atengk.boot.sample.core.dto;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;
import java.util.List;

/**
 * 大批量切片流转与有界租户配额缓存请求对象
 *
 * @author Ateng
 * @since 2026-11-07
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class BatchAndCacheRequest implements Serializable {

    private static final long serialVersionUID = 1L;

    /**
     * 待切片流转的订单流水号列表
     */
    @NotEmpty(message = "待处理订单列表不能为空")
    private List<String> orderCodes;

    /**
     * 单批切片容量大小
     */
    @NotNull(message = "切片大小不能为空")
    @Min(value = 1, message = "切片大小至少为1")
    private Integer sliceSize;

    /**
     * 访问的租户标识
     */
    @NotBlank(message = "租户ID不能为空")
    private String tenantId;

    /**
     * 租户名称
     */
    @NotBlank(message = "租户名称不能为空")
    private String tenantName;

    /**
     * 租户配额上限
     */
    @NotNull(message = "配额上限不能为空")
    @Min(value = 1, message = "配额上限必须大于0")
    private Integer quotaLimit;
}
