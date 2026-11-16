package io.github.atengk.boot.sample.observability.dto;

import jakarta.validation.constraints.NotBlank;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;

/**
 * 临时促销活动指标请求传输对象
 *
 * @author Ateng
 * @since 2026-11-16
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class CampaignMetricRequest implements Serializable {
    private static final long serialVersionUID = 1L;

    /**
     * 促销活动编号
     */
    @NotBlank(message = "促销活动编号不能为空")
    private String campaignId;

    /**
     * 活动投放渠道
     */
    @NotBlank(message = "活动投放渠道不能为空")
    private String channel;
}
