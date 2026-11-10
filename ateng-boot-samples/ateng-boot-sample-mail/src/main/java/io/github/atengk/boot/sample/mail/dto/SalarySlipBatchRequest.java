package io.github.atengk.boot.sample.mail.dto;

import io.github.atengk.boot.core.validation.ValidList;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;

/**
 * 批量工资条合并请求数据传输对象
 *
 * @author Ateng
 * @since 2026-11-10
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class SalarySlipBatchRequest implements Serializable {

    private static final long serialVersionUID = 1L;

    /**
     * 批量员工工资条项列表
     */
    @Valid
    @NotNull(message = "工资条列表不能为空")
    private ValidList<SalarySlipItemRequest> items;
}
