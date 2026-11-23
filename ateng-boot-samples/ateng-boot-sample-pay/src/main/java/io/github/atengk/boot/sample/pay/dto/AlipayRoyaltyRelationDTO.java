package io.github.atengk.boot.sample.pay.dto;

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
 * 支付宝分账关系绑定或解绑请求传输对象
 *
 * @author Ateng
 * @since 2026-11-18
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class AlipayRoyaltyRelationDTO implements Serializable {

    private static final long serialVersionUID = 1L;

    private String merchantKey;

    private String appAuthToken;

    @NotBlank(message = "外部请求号不能为空")
    private String outRequestNo;

    @Valid
    @NotEmpty(message = "分账接收方列表不能为空")
    private List<ReceiverItem> receivers;

    /**
     * 支付宝分账接收方实体明细
     */
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class ReceiverItem implements Serializable {

        private static final long serialVersionUID = 1L;

        @NotBlank(message = "分账方类型不能为空 (userId / loginName / openId)")
        private String type;

        @NotBlank(message = "分账方账号不能为空")
        private String account;

        private String name;

        private String memo;
    }
}
