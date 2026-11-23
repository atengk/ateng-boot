package io.github.atengk.boot.sample.search.dto;

import jakarta.validation.constraints.NotBlank;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 原子别名热切换请求 DTO (swapAlias)
 *
 * @author Ateng
 * @since 2026-11-11
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class AliasSwapRequest {

    @NotBlank(message = "别名名称不能为空")
    private String aliasName;

    /**
     * 旧物理索引（可为空，为空时仅执行添加别名操作）
     */
    private String oldIndex;

    @NotBlank(message = "新物理索引不能为空")
    private String newIndex;
}
