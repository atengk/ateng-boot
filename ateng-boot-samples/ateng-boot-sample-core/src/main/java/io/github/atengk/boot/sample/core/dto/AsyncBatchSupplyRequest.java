package io.github.atengk.boot.sample.core.dto;

import jakarta.validation.constraints.NotEmpty;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;
import java.util.List;

/**
 * 异步批量任务强类型汇聚请求对象
 *
 * @author Ateng
 * @since 2026-11-09
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class AsyncBatchSupplyRequest implements Serializable {

    private static final long serialVersionUID = 1L;

    /**
     * 待查询的报表指标项
     */
    @NotEmpty(message = "指标查询列表不能为空")
    private List<String> metrics;
}
