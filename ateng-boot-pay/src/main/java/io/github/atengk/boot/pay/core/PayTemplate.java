package io.github.atengk.boot.pay.core;

import io.github.atengk.boot.pay.core.context.PayContextHolder;
import io.github.atengk.boot.pay.enums.PayChannel;
import io.github.atengk.boot.pay.enums.PayResultCode;
import io.github.atengk.boot.pay.enums.PayTradeType;
import io.github.atengk.boot.pay.exception.PayException;
import io.github.atengk.boot.pay.model.request.AlipayPayRequest;
import io.github.atengk.boot.pay.model.request.UnifiedCloseRequest;
import io.github.atengk.boot.pay.model.request.UnifiedPayRequest;
import io.github.atengk.boot.pay.model.request.UnifiedQueryRequest;
import io.github.atengk.boot.pay.model.request.WechatPayRequest;
import io.github.atengk.boot.pay.model.response.PayResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Objects;

/**
 * 顶层轻量统一收单门面
 * <p>
 * 抹平微信支付与支付宝渠道底层协议差异，提供高直觉统一收单入口（{@link #pay}、{@link #queryOrder}、{@link #closeOrder}）：
 * <ol>
 *     <li><b>双渠道智能路由</b>：依据入参 {@code channel} 或上下文 {@link PayContextHolder#getChannel()} 自动分发至专有底层门面；</li>
 *     <li><b>扁平直达凭据出参</b>：统一返回扁平化 {@link PayResponse}，场景凭据（{@code qrCode}、{@code payUrl}、{@code formHtml}、{@code wechatJsapi}、{@code orderString}）直接消费，杜绝 {@code instanceof} 强转；</li>
 *     <li><b>严格货币与元精度治理</b>：全域强制校验 {@link BigDecimal}（元）精度（小数位严禁超过 2 位），确保分/元转换数学绝对精确；</li>
 *     <li><b>官方专有门面逃生舱</b>：随时可通过 {@link #getWechatPayTemplate()} 或 {@link #getAlipayTemplate()} 下沉至专有渠道执行深度定制能力。</li>
 * </ol>
 * </p>
 *
 * @author Ateng
 * @since 2026-10-23
 */
public class PayTemplate {

    private static final Logger log = LoggerFactory.getLogger(PayTemplate.class);

    private final WechatPayTemplate wechatPayTemplate;
    private final AlipayTemplate alipayTemplate;

    public PayTemplate(WechatPayTemplate wechatPayTemplate, AlipayTemplate alipayTemplate) {
        this.wechatPayTemplate = wechatPayTemplate;
        this.alipayTemplate = alipayTemplate;
    }

    // =========================================================================
    // 1. 统一收单 (pay)
    // =========================================================================

    /**
     * 发起统一全场景收单
     *
     * @param request 统一收单请求入参
     * @return 扁平化统一收单出参（前端按需直接消费各场景凭据字段）
     */
    public PayResponse pay(UnifiedPayRequest request) {
        validatePayRequest(request);
        PayChannel channel = resolveChannel(request.getChannel());
        PayTradeType tradeType = request.getTradeType();
        BigDecimal normalizedAmount = request.getAmount().setScale(2, RoundingMode.HALF_UP);

        log.debug("Routing unified payment request: channel={}, tradeType={}, outTradeNo={}, amount={}",
                channel, tradeType, request.getOutTradeNo(), normalizedAmount);

        return switch (channel) {
            case WECHAT_PAY -> {
                if (wechatPayTemplate == null) {
                    throw new PayException(PayResultCode.PAY_GATEWAY_ERROR, "微信支付模块未装配或未启用 (ateng.boot.pay.wechat.enabled=false)");
                }
                WechatPayRequest wechatReq = WechatPayRequest.builder()
                        .merchantKey(request.getMerchantKey())
                        .outTradeNo(request.getOutTradeNo())
                        .description(resolveSubject(request))
                        .amount(normalizedAmount)
                        .currency(request.getCurrency())
                        .notifyUrl(request.getNotifyUrl())
                        .openid(request.getOpenid())
                        .subOpenid(request.getSubOpenid())
                        .authCode(request.getAuthCode())
                        .attach(request.getAttach())
                        .timeExpire(request.getTimeExpire())
                        .spMchId(request.getSpMchId())
                        .subMchId(request.getSubMchId())
                        .spAppId(request.getSpAppId())
                        .subAppId(request.getSubAppId())
                        .build();

                yield switch (tradeType) {
                    case NATIVE -> wechatPayTemplate.payNative(wechatReq);
                    case JSAPI -> wechatPayTemplate.payJsapi(wechatReq);
                    case H5 -> wechatPayTemplate.payH5(wechatReq);
                    case APP -> wechatPayTemplate.payApp(wechatReq);
                    case BARCODE -> wechatPayTemplate.payBarcode(wechatReq);
                    case PAGE -> throw new PayException(PayResultCode.PAY_PARAM_INVALID,
                            "微信支付不支持 PAGE 电脑网站收单场景，推荐选用 NATIVE 扫码支付");
                };
            }
            case ALIPAY -> {
                if (alipayTemplate == null) {
                    throw new PayException(PayResultCode.PAY_GATEWAY_ERROR, "支付宝模块未装配或未启用 (ateng.boot.pay.alipay.enabled=false)");
                }
                AlipayPayRequest alipayReq = AlipayPayRequest.builder()
                        .appKey(request.getMerchantKey())
                        .outTradeNo(request.getOutTradeNo())
                        .subject(resolveSubject(request))
                        .amount(normalizedAmount)
                        .notifyUrl(request.getNotifyUrl())
                        .returnUrl(request.getReturnUrl())
                        .authCode(request.getAuthCode())
                        .body(request.getBody() != null ? request.getBody() : request.getAttach())
                        .timeExpire(request.getTimeExpire())
                        .timeoutExpress(request.getTimeoutExpress())
                        .passbackParams(request.getAttach())
                        .appAuthToken(request.getAppAuthToken())
                        .build();

                yield switch (tradeType) {
                    case PAGE -> alipayTemplate.payPage(alipayReq);
                    case H5 -> alipayTemplate.payWap(alipayReq);
                    case APP -> alipayTemplate.payApp(alipayReq);
                    case BARCODE -> alipayTemplate.payBarcode(alipayReq);
                    case NATIVE -> alipayTemplate.payQr(alipayReq);
                    case JSAPI -> throw new PayException(PayResultCode.PAY_PARAM_INVALID,
                            "支付宝 JSAPI 小程序收单暂请使用专有接口或选用当面付扫码/条码场景");
                };
            }
        };
    }

    // =========================================================================
    // 2. 统一订单查询 (queryOrder)
    // =========================================================================

    /**
     * 主动查询订单交易状态
     *
     * @param request 统一查单请求入参
     * @return 统一交易状态出参
     */
    public PayResponse queryOrder(UnifiedQueryRequest request) {
        if (request == null) {
            throw new PayException(PayResultCode.PAY_PARAM_INVALID, "查单请求参数不能为空");
        }
        PayChannel channel = resolveChannel(request.getChannel());

        return switch (channel) {
            case WECHAT_PAY -> {
                if (wechatPayTemplate == null) {
                    throw new PayException(PayResultCode.PAY_GATEWAY_ERROR, "微信支付模块未装配或未启用");
                }
                WechatPayRequest wechatReq = WechatPayRequest.builder()
                        .merchantKey(request.getMerchantKey())
                        .outTradeNo(request.getOutTradeNo())
                        .transactionId(request.getTransactionId())
                        .subMchId(request.getSubMchId())
                        .build();
                yield wechatPayTemplate.queryOrder(wechatReq);
            }
            case ALIPAY -> {
                if (alipayTemplate == null) {
                    throw new PayException(PayResultCode.PAY_GATEWAY_ERROR, "支付宝模块未装配或未启用");
                }
                AlipayPayRequest alipayReq = AlipayPayRequest.builder()
                        .appKey(request.getMerchantKey())
                        .outTradeNo(request.getOutTradeNo())
                        .tradeNo(request.getTransactionId())
                        .appAuthToken(request.getAppAuthToken())
                        .build();
                yield alipayTemplate.queryOrder(alipayReq);
            }
        };
    }

    /**
     * 单标识符重载：根据渠道与商户订单号查询订单
     *
     * @param channel    支付渠道
     * @param outTradeNo 商户订单号
     * @return 统一交易状态出参
     */
    public PayResponse queryOrder(PayChannel channel, String outTradeNo) {
        return queryOrder(UnifiedQueryRequest.builder().channel(channel).outTradeNo(outTradeNo).build());
    }

    /**
     * 单标识符重载：根据渠道、商户别名与商户订单号查询订单
     *
     * @param channel     支付渠道
     * @param merchantKey 商户/应用别名
     * @param outTradeNo  商户订单号
     * @return 统一交易状态出参
     */
    public PayResponse queryOrder(PayChannel channel, String merchantKey, String outTradeNo) {
        return queryOrder(UnifiedQueryRequest.builder().channel(channel).merchantKey(merchantKey).outTradeNo(outTradeNo).build());
    }

    // =========================================================================
    // 3. 统一订单关闭 (closeOrder)
    // =========================================================================

    /**
     * 关闭未付款的订单
     *
     * @param request 统一关单请求入参
     * @return 关单操作响应
     */
    public PayResponse closeOrder(UnifiedCloseRequest request) {
        if (request == null) {
            throw new PayException(PayResultCode.PAY_PARAM_INVALID, "关单请求参数不能为空");
        }
        PayChannel channel = resolveChannel(request.getChannel());

        return switch (channel) {
            case WECHAT_PAY -> {
                if (wechatPayTemplate == null) {
                    throw new PayException(PayResultCode.PAY_GATEWAY_ERROR, "微信支付模块未装配或未启用");
                }
                WechatPayRequest wechatReq = WechatPayRequest.builder()
                        .merchantKey(request.getMerchantKey())
                        .outTradeNo(request.getOutTradeNo())
                        .subMchId(request.getSubMchId())
                        .build();
                yield wechatPayTemplate.closeOrder(wechatReq);
            }
            case ALIPAY -> {
                if (alipayTemplate == null) {
                    throw new PayException(PayResultCode.PAY_GATEWAY_ERROR, "支付宝模块未装配或未启用");
                }
                AlipayPayRequest alipayReq = AlipayPayRequest.builder()
                        .appKey(request.getMerchantKey())
                        .outTradeNo(request.getOutTradeNo())
                        .appAuthToken(request.getAppAuthToken())
                        .build();
                yield alipayTemplate.closeOrder(alipayReq);
            }
        };
    }

    /**
     * 单标识符重载：根据渠道与商户订单号关闭未付款订单
     *
     * @param channel    支付渠道
     * @param outTradeNo 商户订单号
     * @return 关单操作响应
     */
    public PayResponse closeOrder(PayChannel channel, String outTradeNo) {
        return closeOrder(UnifiedCloseRequest.builder().channel(channel).outTradeNo(outTradeNo).build());
    }

    /**
     * 单标识符重载：根据渠道、商户别名与商户订单号关闭未付款订单
     *
     * @param channel     支付渠道
     * @param merchantKey 商户/应用别名
     * @param outTradeNo  商户订单号
     * @return 关单操作响应
     */
    public PayResponse closeOrder(PayChannel channel, String merchantKey, String outTradeNo) {
        return closeOrder(UnifiedCloseRequest.builder().channel(channel).merchantKey(merchantKey).outTradeNo(outTradeNo).build());
    }

    // =========================================================================
    // 4. 专有门面逃生舱
    // =========================================================================

    /**
     * 获取底层微信支付专有门面实例（可为 null）
     */
    public WechatPayTemplate getWechatPayTemplate() {
        return wechatPayTemplate;
    }

    /**
     * 获取底层支付宝专有门面实例（可为 null）
     */
    public AlipayTemplate getAlipayTemplate() {
        return alipayTemplate;
    }

    // =========================================================================
    // 内部私有校验与辅助逻辑
    // =========================================================================

    private void validatePayRequest(UnifiedPayRequest request) {
        if (request == null) {
            throw new PayException(PayResultCode.PAY_PARAM_INVALID, "统一收单请求参数不能为空");
        }
        if (request.getOutTradeNo() == null || request.getOutTradeNo().isBlank()) {
            throw new PayException(PayResultCode.PAY_PARAM_INVALID, "商户订单号 [outTradeNo] 不能为空");
        }
        if ((request.getSubject() == null || request.getSubject().isBlank())
                && (request.getDescription() == null || request.getDescription().isBlank())) {
            throw new PayException(PayResultCode.PAY_PARAM_INVALID, "订单标题 [subject / description] 不能为空");
        }
        if (request.getAmount() == null || request.getAmount().compareTo(BigDecimal.ZERO) <= 0) {
            throw new PayException(PayResultCode.PAY_AMOUNT_INVALID, "订单金额 [amount] 必须大于 0 元");
        }
        // 校验金额精度：小数位不得超过 2 位（分）
        BigDecimal stripped = request.getAmount().stripTrailingZeros();
        if (stripped.scale() > 2) {
            throw new PayException(PayResultCode.PAY_AMOUNT_INVALID,
                    "订单金额小数位不能超过 2 位（分）：" + request.getAmount());
        }
        if (request.getTradeType() == null) {
            throw new PayException(PayResultCode.PAY_PARAM_INVALID, "交易场景类型 [tradeType] 不能为空");
        }
    }

    private PayChannel resolveChannel(PayChannel explicitChannel) {
        if (explicitChannel != null) {
            return explicitChannel;
        }
        PayChannel contextChannel = PayContextHolder.getChannel();
        if (contextChannel != null) {
            return contextChannel;
        }
        throw new PayException(PayResultCode.PAY_PARAM_INVALID,
                "未指定支付渠道 [channel]，请在方法入参或 PayContextHolder 上下文中指定");
    }

    private String resolveSubject(UnifiedPayRequest request) {
        if (request.getSubject() != null && !request.getSubject().isBlank()) {
            return request.getSubject();
        }
        return request.getDescription();
    }
}
