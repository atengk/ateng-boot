package io.github.atengk.boot.sample.mybatisplus.model;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;

import java.io.Serializable;

/**
 * 创建多租户泛型凭证请求参数
 *
 * @author Ateng
 * @since 2026-11-20
 */
@Data
public class TenantVoucherCreateDTO implements Serializable {

    private static final long serialVersionUID = 1L;

    @NotBlank(message = "凭证编号不能为空")
    @Size(max = 64, message = "凭证编号长度不能超过 64")
    private String voucherNo;

    @NotBlank(message = "凭证标题不能为空")
    @Size(max = 128, message = "凭证标题长度不能超过 128")
    private String title;
}
