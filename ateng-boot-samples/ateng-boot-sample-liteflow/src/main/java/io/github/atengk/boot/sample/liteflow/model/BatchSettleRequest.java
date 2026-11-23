package io.github.atengk.boot.sample.liteflow.model;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;
import java.util.List;

/**
 * 批量路由结算请求参数
 *
 * @author Ateng
 * @since 2026-11-06
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class BatchSettleRequest implements Serializable {

    private static final long serialVersionUID = 1L;

    /**
     * 商户标识
     */
    @NotBlank(message = "商户标识 (merchantId) 不能为空")
    private String merchantId;

    /**
     * 结算批次号
     */
    @NotBlank(message = "结算批次号 (batchNo) 不能为空")
    private String batchNo;

    /**
     * 遭遇可疑风险明细时是否触发 endFlow 提前正常熔断终止批次
     */
    private boolean terminateOnSuspicious;

    /**
     * 结算明细列表
     */
    @NotEmpty(message = "结算明细列表 (items) 不能为空")
    @Valid
    private List<SettleItem> items;
}
