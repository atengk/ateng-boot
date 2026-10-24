package io.github.atengk.boot.pay.model.bill;

import io.github.atengk.boot.pay.enums.PayChannel;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;

/**
 * 渠道财务对账单下载统一入参请求
 *
 * @author Ateng
 * @since 2026-10-24
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class PayBillDownloadRequest implements Serializable {

    private static final long serialVersionUID = 1L;

    /**
     * 支付渠道（必填：WECHAT_PAY / ALIPAY）
     */
    private PayChannel channel;

    /**
     * 多商户/多应用配置标识（留空选用默认配置）
     */
    private String merchantKey;

    /**
     * 账单日期，格式 yyyy-MM-dd（必填，如 2026-10-24）
     */
    private String billDate;

    /**
     * 账单类型：
     * <p>微信支付支持：ALL（当日所有订单，默认）、SUCCESS（成功支付订单）、REFUND（退款订单）</p>
     * <p>支付宝支持：trade（商户基于支付宝交易收单的业务账单，默认）、signcustomer（基于商户支付宝余额收入及支出等资金变动的账务账单）</p>
     */
    private String billType;

    /**
     * 压缩格式（微信支持 GZIP，传 GZIP 则返回 gzip 压缩流）
     */
    private String tarType;

    /**
     * 微信特约商户号（服务商模式下载特约商户账单时填写）
     */
    private String subMchid;

    /**
     * 支付宝二级商户smid
     */
    private String smid;
}
