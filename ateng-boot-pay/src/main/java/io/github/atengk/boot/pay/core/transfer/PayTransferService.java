package io.github.atengk.boot.pay.core.transfer;

import io.github.atengk.boot.pay.core.AlipayTemplate;
import io.github.atengk.boot.pay.core.PayTemplate;
import io.github.atengk.boot.pay.core.WechatPayTemplate;
import io.github.atengk.boot.pay.enums.PayChannel;
import io.github.atengk.boot.pay.enums.PayResultCode;
import io.github.atengk.boot.pay.exception.PayException;
import io.github.atengk.boot.pay.model.request.AlipayTransferRequest;
import io.github.atengk.boot.pay.model.request.UnifiedTransferQueryRequest;
import io.github.atengk.boot.pay.model.request.UnifiedTransferRequest;
import io.github.atengk.boot.pay.model.request.WechatTransferRequest;
import io.github.atengk.boot.pay.model.response.PayTransferResponse;

import java.util.Objects;

/**
 * 单笔资金转账高敏业务服务门面
 * <p>
 * 仅在 {@code ateng.boot.pay.transfer.enabled=true} 时通过自动装配生效，
 * 封装微信支付（商家转账到零钱）与支付宝（单笔无密转账到账户/银行卡）的完整业务闭环。
 * </p>
 *
 * @author Ateng
 * @since 2026-10-24
 */
public class PayTransferService {

    private final PayTemplate payTemplate;

    public PayTransferService(PayTemplate payTemplate) {
        this.payTemplate = Objects.requireNonNull(payTemplate, "PayTemplate 不能为空");
    }

    /**
     * 发起统一单笔资金转账
     *
     * @param request 统一转账请求入参
     * @return 统一转账响应出参
     */
    public PayTransferResponse transfer(UnifiedTransferRequest request) {
        return payTemplate.transfer(request);
    }

    /**
     * 查询统一转账状态
     *
     * @param request 统一查单请求入参
     * @return 统一转账响应出参
     */
    public PayTransferResponse queryTransfer(UnifiedTransferQueryRequest request) {
        return payTemplate.queryTransfer(request);
    }

    /**
     * 根据渠道与商户转账单号查询转账状态
     *
     * @param channel  支付渠道
     * @param outBizNo 商户转账唯一单号
     * @return 统一转账响应出参
     */
    public PayTransferResponse queryTransfer(PayChannel channel, String outBizNo) {
        return payTemplate.queryTransfer(channel, outBizNo);
    }

    /**
     * 根据渠道、商户转账单号与渠道转账单号查询转账状态
     *
     * @param channel  支付渠道
     * @param outBizNo 商户转账唯一单号
     * @param orderId  渠道转账单号（微信 batchId 或 支付宝 orderId）
     * @return 统一转账响应出参
     */
    public PayTransferResponse queryTransfer(PayChannel channel, String outBizNo, String orderId) {
        return payTemplate.queryTransfer(channel, outBizNo, orderId);
    }

    /**
     * 撤销待确认单笔转账（默认渠道）
     *
     * @param outBizNo 商户转账唯一单号
     * @return 统一转账响应出参
     */
    public PayTransferResponse cancelTransfer(String outBizNo) {
        return payTemplate.cancelTransfer(outBizNo);
    }

    /**
     * 撤销待确认单笔转账（指定渠道）
     *
     * @param channel  支付渠道
     * @param outBizNo 商户转账唯一单号
     * @return 统一转账响应出参
     */
    public PayTransferResponse cancelTransfer(PayChannel channel, String outBizNo) {
        return payTemplate.cancelTransfer(channel, outBizNo);
    }

    /**
     * 撤销待确认单笔转账（指定渠道与商户路由键）
     *
     * @param channel     支付渠道
     * @param merchantKey 商户别名（可选）
     * @param outBizNo    商户转账唯一单号
     * @return 统一转账响应出参
     */
    public PayTransferResponse cancelTransfer(PayChannel channel, String merchantKey, String outBizNo) {
        return payTemplate.cancelTransfer(channel, merchantKey, outBizNo);
    }

    /**
     * 专有直达：发起微信商家转账到零钱
     *
     * @param request 微信转账专有请求
     * @return 统一转账响应出参
     */
    public PayTransferResponse transferWechat(WechatTransferRequest request) {
        return requireWechatTemplate().transfer(request);
    }

    /**
     * 专有直达：查询微信商家转账明细状态（旧版批次模型显式兼容）
     *
     * @param merchantKey 商户别名（可选）
     * @param outBatchNo  商家批次单号
     * @param outDetailNo 商家明细单号
     * @return 统一转账响应出参
     */
    public PayTransferResponse queryWechatTransfer(String merchantKey, String outBatchNo, String outDetailNo) {
        return requireWechatTemplate().queryTransferDetail(merchantKey, outBatchNo, outDetailNo);
    }

    /**
     * 专有直达：查询微信新版商家转账单状态（默认商户，按商户转账单号）
     *
     * @param outBillNo 商户转账单号
     * @return 统一转账响应出参
     */
    public PayTransferResponse queryWechatTransfer(String outBillNo) {
        return requireWechatTemplate().queryTransfer(outBillNo);
    }

    /**
     * 专有直达：查询微信新版商家转账单状态（支持商户转账单号与微信转账单号二选一）
     *
     * @param merchantKey    商户别名（可选）
     * @param outBillNo      商户转账单号（优先）
     * @param transferBillNo 微信转账单号
     * @return 统一转账响应出参
     */
    public PayTransferResponse queryWechatTransferBill(String merchantKey, String outBillNo, String transferBillNo) {
        return requireWechatTemplate().queryTransfer(merchantKey, outBillNo, transferBillNo);
    }

    /**
     * 专有直达：撤销待用户确认的微信新版商家转账单（默认商户）
     *
     * @param outBillNo 商户转账单号
     * @return 统一转账响应出参
     */
    public PayTransferResponse cancelWechatTransfer(String outBillNo) {
        return requireWechatTemplate().cancelTransfer(outBillNo);
    }

    /**
     * 专有直达：撤销待用户确认的微信新版商家转账单（指定商户）
     *
     * @param merchantKey 商户别名（可选）
     * @param outBillNo   商户转账单号
     * @return 统一转账响应出参
     */
    public PayTransferResponse cancelWechatTransfer(String merchantKey, String outBillNo) {
        return requireWechatTemplate().cancelTransfer(merchantKey, outBillNo);
    }

    /**
     * 专有直达：发起支付宝单笔转账
     *
     * @param request 支付宝转账专有请求
     * @return 统一转账响应出参
     */
    public PayTransferResponse transferAlipay(AlipayTransferRequest request) {
        return requireAlipayTemplate().transfer(request);
    }

    /**
     * 专有直达：查询支付宝单笔转账状态
     *
     * @param appKey   应用别名（可选）
     * @param outBizNo 商户转账唯一单号
     * @param orderId  支付宝转账单据号（可选）
     * @return 统一转账响应出参
     */
    public PayTransferResponse queryAlipayTransfer(String appKey, String outBizNo, String orderId) {
        return requireAlipayTemplate().queryTransfer(appKey, outBizNo, orderId);
    }

    private WechatPayTemplate requireWechatTemplate() {
        WechatPayTemplate wechatTemplate = payTemplate.getWechatPayTemplate();
        if (wechatTemplate == null) {
            throw new PayException(PayResultCode.PAY_GATEWAY_ERROR, "微信支付模块未装配或未启用");
        }
        return wechatTemplate;
    }

    private AlipayTemplate requireAlipayTemplate() {
        AlipayTemplate alipayTemplate = payTemplate.getAlipayTemplate();
        if (alipayTemplate == null) {
            throw new PayException(PayResultCode.PAY_GATEWAY_ERROR, "支付宝模块未装配或未启用");
        }
        return alipayTemplate;
    }
}
