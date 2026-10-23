package io.github.atengk.boot.sample.document.model;

import jakarta.validation.constraints.NotBlank;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 供应商资质准入表单填报请求对象
 *
 * @author Ateng
 * @since 2026-10-23
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class SupplierFormRequest {

    /**
     * 供应商企业名称
     */
    @NotBlank(message = "供应商企业名称不能为空")
    private String supplierName;

    /**
     * 业务联系人电话
     */
    @NotBlank(message = "联系人电话不能为空")
    private String contactPhone;

    /**
     * 资质审核是否通过（复选框）
     */
    @Builder.Default
    private Boolean qualificationPassed = true;
}
