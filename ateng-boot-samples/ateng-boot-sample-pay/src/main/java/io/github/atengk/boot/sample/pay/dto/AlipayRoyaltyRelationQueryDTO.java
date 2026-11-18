package io.github.atengk.boot.sample.pay.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;

/**
 * 支付宝分账关系分页批量查询传输对象
 *
 * @author Ateng
 * @since 2026-11-18
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class AlipayRoyaltyRelationQueryDTO implements Serializable {

    private static final long serialVersionUID = 1L;

    private String merchantKey;

    private String appAuthToken;

    @NotBlank(message = "外部请求号不能为空")
    private String outRequestNo;

    @Min(value = 1, message = "页码最小为 1")
    @Builder.Default
    private Long pageNum = 1L;

    @Min(value = 1, message = "每页条数最小为 1")
    @Max(value = 100, message = "每页条数最大为 100")
    @Builder.Default
    private Long pageSize = 20L;
}
