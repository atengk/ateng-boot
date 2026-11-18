package io.github.atengk.boot.pay.model.request;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;

/**
 * 微信新版商家转账场景报备明细项
 * <p>
 * 对应微信支付 V3 {@code transfer_scene_report_infos} 节点，用于按核准转账场景上报合规信息类型与说明内容。
 * </p>
 *
 * @author Ateng
 * @since 2026-11-18
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class TransferSceneReportInfo implements Serializable {

    private static final long serialVersionUID = 1L;

    /**
     * 信息类型（如：活动名称、奖励说明、岗位类型、报酬说明等）
     */
    private String infoType;

    /**
     * 信息内容（与 infoType 对应的真实业务描述）
     */
    private String infoContent;
}
