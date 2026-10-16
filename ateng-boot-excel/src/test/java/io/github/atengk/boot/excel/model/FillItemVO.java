package io.github.atengk.boot.excel.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;

/**
 * 单元测试模型：用于测试 Excel 模板列表循环填充
 *
 * @author Ateng
 * @since 2026-10-16
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class FillItemVO implements Serializable {

    private static final long serialVersionUID = 1L;

    private String name;
    private Double amount;
}
