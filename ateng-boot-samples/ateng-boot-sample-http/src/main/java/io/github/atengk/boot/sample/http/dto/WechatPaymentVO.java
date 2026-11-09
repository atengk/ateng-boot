package io.github.atengk.boot.sample.http.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;

/**
 * 微信支付统一下单响应视图对象
 * 包含二维码支付链接与内网敏感头剥离验证结果
 *
 * @author Ateng
 * @since 2026-11-09
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class WechatPaymentVO implements Serializable {

    private static final long serialVersionUID = 1L;

    /**
     * 商户订单号
     */
    private String outTradeNo;

    /**
     * 微信 Native 预支付二维码链接
     */
    private String codeUrl;

    /**
     * 是否成功物理剥离内网敏感身份头 (X-Tenant-Id / X-User-Id)
     */
    private boolean contextStripped;

    /**
     * 透传保留的分布式全链路追踪标识 (X-Trace-Id)
     */
    private String traceId;
}
