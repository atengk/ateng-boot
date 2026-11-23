package io.github.atengk.boot.sample.pay.dto;

import jakarta.validation.constraints.NotBlank;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;

/**
 * 微信新版商家转账场景报备明细传输对象
 *
 * @author Ateng
 * @since 2026-11-18
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class PayTransferSceneReportInfoDTO implements Serializable {

    private static final long serialVersionUID = 1L;

    /**
     * 信息类型（如：活动名称、奖励说明）
     */
    @NotBlank(message = "场景报备信息类型不能为空")
    private String infoType;

    /**
     * 信息内容
     */
    @NotBlank(message = "场景报备信息内容不能为空")
    private String infoContent;
}
