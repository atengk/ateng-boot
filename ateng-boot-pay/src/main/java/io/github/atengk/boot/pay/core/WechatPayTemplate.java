package io.github.atengk.boot.pay.core;

import com.wechat.pay.java.core.AbstractRSAConfig;
import com.wechat.pay.java.core.Config;
import com.wechat.pay.java.core.RSAPublicKeyConfig;
import com.wechat.pay.java.core.certificate.CertificateProvider;
import com.wechat.pay.java.core.exception.HttpException;
import com.wechat.pay.java.core.exception.MalformedMessageException;
import com.wechat.pay.java.core.exception.ServiceException;
import com.wechat.pay.java.core.exception.ValidationException;
import com.wechat.pay.java.core.http.*;
import com.wechat.pay.java.service.billdownload.BillDownloadService;
import com.wechat.pay.java.service.billdownload.BillDownloadServiceExtension;
import com.wechat.pay.java.service.billdownload.DigestBillEntity;
import com.wechat.pay.java.service.billdownload.model.BillType;
import com.wechat.pay.java.service.billdownload.model.GetFundFlowBillRequest;
import com.wechat.pay.java.service.billdownload.model.GetTradeBillRequest;
import com.wechat.pay.java.service.billdownload.model.QueryBillEntity;
import com.wechat.pay.java.service.billdownload.model.TarType;
import com.wechat.pay.java.service.payments.app.AppServiceExtension;
import com.wechat.pay.java.service.payments.h5.H5Service;
import com.wechat.pay.java.service.payments.h5.model.H5Info;
import com.wechat.pay.java.service.payments.h5.model.SceneInfo;
import com.wechat.pay.java.service.payments.jsapi.JsapiServiceExtension;
import com.wechat.pay.java.service.payments.jsapi.model.CloseOrderRequest;
import com.wechat.pay.java.service.payments.jsapi.model.Payer;
import com.wechat.pay.java.service.payments.jsapi.model.PrepayWithRequestPaymentResponse;
import com.wechat.pay.java.service.payments.jsapi.model.QueryOrderByIdRequest;
import com.wechat.pay.java.service.payments.jsapi.model.QueryOrderByOutTradeNoRequest;
import com.wechat.pay.java.service.payments.model.Transaction;
import com.wechat.pay.java.service.payments.nativepay.NativePayService;
import com.wechat.pay.java.service.profitsharing.ProfitsharingService;
import com.wechat.pay.java.service.profitsharing.model.AddReceiverRequest;
import com.wechat.pay.java.service.profitsharing.model.AddReceiverResponse;
import com.wechat.pay.java.service.profitsharing.model.CreateOrderRequest;
import com.wechat.pay.java.service.profitsharing.model.CreateReturnOrderRequest;
import com.wechat.pay.java.service.profitsharing.model.DeleteReceiverRequest;
import com.wechat.pay.java.service.profitsharing.model.DeleteReceiverResponse;
import com.wechat.pay.java.service.profitsharing.model.OrdersEntity;
import com.wechat.pay.java.service.profitsharing.model.QueryMerchantRatioRequest;
import com.wechat.pay.java.service.profitsharing.model.QueryMerchantRatioResponse;
import com.wechat.pay.java.service.profitsharing.model.QueryOrderAmountRequest;
import com.wechat.pay.java.service.profitsharing.model.QueryOrderAmountResponse;
import com.wechat.pay.java.service.profitsharing.model.QueryOrderRequest;
import com.wechat.pay.java.service.profitsharing.model.QueryReturnOrderRequest;
import com.wechat.pay.java.service.profitsharing.model.ReturnOrdersEntity;
import com.wechat.pay.java.service.profitsharing.model.UnfreezeOrderRequest;
import com.wechat.pay.java.service.refund.RefundService;
import com.wechat.pay.java.service.refund.model.AmountReq;
import com.wechat.pay.java.service.refund.model.CreateRequest;
import com.wechat.pay.java.service.refund.model.QueryByOutRefundNoRequest;
import com.wechat.pay.java.service.refund.model.Refund;
import com.wechat.pay.java.service.refund.model.ReqFundsAccount;
import com.wechat.pay.java.service.refund.model.Status;
import com.wechat.pay.java.service.transferbatch.TransferBatchService;
import com.wechat.pay.java.service.transferbatch.model.*;
import io.github.atengk.boot.core.util.DesensitizeUtil;
import io.github.atengk.boot.core.util.JsonUtil;
import io.github.atengk.boot.core.util.RetryUtil;
import io.github.atengk.boot.pay.config.properties.PayProfitSharingProperties;
import io.github.atengk.boot.pay.config.properties.PayProperties;
import io.github.atengk.boot.pay.config.properties.PayTransferProperties;
import io.github.atengk.boot.pay.config.properties.WechatMerchantProperties;
import io.github.atengk.boot.pay.core.certificate.WechatAutoCertificateManager;
import io.github.atengk.boot.pay.core.merchant.NamedMerchantRegistry;
import io.github.atengk.boot.pay.enums.PayChannel;
import io.github.atengk.boot.pay.enums.PayRefundState;
import io.github.atengk.boot.pay.enums.PayResultCode;
import io.github.atengk.boot.pay.enums.PayTradeState;
import io.github.atengk.boot.pay.enums.PayTradeType;
import io.github.atengk.boot.pay.enums.PayTransferState;
import io.github.atengk.boot.pay.exception.PayException;
import io.github.atengk.boot.pay.exception.PayNetworkException;
import io.github.atengk.boot.pay.exception.PaySignException;
import io.github.atengk.boot.pay.exception.PayTradeException;
import io.github.atengk.boot.pay.model.request.UnifiedRefundRequest;
import io.github.atengk.boot.pay.model.request.WechatPayRequest;
import io.github.atengk.boot.pay.model.request.WechatRefundRequest;
import io.github.atengk.boot.pay.model.request.WechatTransferRequest;
import io.github.atengk.boot.pay.model.response.PayRefundResponse;
import io.github.atengk.boot.pay.model.response.PayResponse;
import io.github.atengk.boot.pay.model.response.PayTransferResponse;
import io.github.atengk.boot.pay.model.response.WechatAppParams;
import io.github.atengk.boot.pay.model.response.WechatJsapiParams;
import io.github.atengk.boot.pay.util.CurrencyUtil;
import io.github.atengk.boot.pay.util.PayKeyLoader;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.InputStream;
import java.math.BigDecimal;
import java.time.Duration;
import java.security.KeyFactory;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.security.cert.X509Certificate;
import java.security.spec.X509EncodedKeySpec;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;

/**
 * 微信支付专有收单与高阶能力执行门面
 * <p>
 * 基于腾讯官方新一代权威 {@code wechatpay-java:0.2.17} SDK 驱动，具备以下核心工业级特性：
 * <ol>
 *     <li><b>5 大核心收单场景全覆盖</b>：原生支持 Native（扫码）、JSAPI（小程序/公众号）、H5、App、条码付（被扫付款码）；</li>
 *     <li><b>服务端全自动二次加签与封包</b>：针对 JSAPI 和 App 统一下单，全自动在服务端使用商户私钥计算数字签名并封包直达凭据；</li>
 *     <li><b>直连商户与服务商双模自适应</b>：原生兼容直连商户号与服务商（ISV）特约子商户模式（{@code sub_mchid}）；</li>
 *     <li><b>多商户与动态切源</b>：协同 {@link NamedMerchantRegistry} 与 {@link WechatAutoCertificateManager} 实现无缝切源与平台证书自动轮换；</li>
 *     <li><b>全域强类型与因果异常转译</b>：入参统一 {@code BigDecimal} 元，底层驱动异常完整保留因果链转译为语义化业务异常；</li>
 *     <li><b>官方客户端逃生舱</b>：随时可通过 {@link #getHttpClient(String)} 或 {@link #getWechatConfig(String)} 获取底层原生对象。</li>
 * </ol>
 * </p>
 *
 * @author Ateng
 * @since 2026-10-23
 */
public class WechatPayTemplate {

    private static final Logger log = LoggerFactory.getLogger(WechatPayTemplate.class);

    private static final String DIRECT_CODEPAY_URL = "https://api.mch.weixin.qq.com/v3/pay/transactions/codepay";
    private static final String PARTNER_CODEPAY_URL = "https://api.mch.weixin.qq.com/v3/pay/partner/transactions/codepay";
    private static final String DIRECT_REVERSE_URL = "https://api.mch.weixin.qq.com/v3/pay/transactions/out-trade-no/%s/reverse";
    private static final String PARTNER_REVERSE_URL = "https://api.mch.weixin.qq.com/v3/pay/partner/transactions/out-trade-no/%s/reverse";

    private final NamedMerchantRegistry namedMerchantRegistry;
    private final WechatAutoCertificateManager wechatAutoCertificateManager;
    private final PayProperties payProperties;

    /**
     * 微信线下条码付撤销冲正重试策略（默认最多 3 次，间隔 3 秒）
     */
    private volatile RetryUtil.RetryConfig wechatReverseRetryConfig = RetryUtil.RetryConfig.builder()
            .maxAttempts(3)
            .delay(Duration.ofSeconds(3))
            .multiplier(1.0)
            .maxDelay(Duration.ofSeconds(3))
            .retryPredicate(throwable -> {
                if (throwable instanceof HttpException) {
                    return true;
                }
                if (throwable instanceof ServiceException se) {
                    int status = se.getHttpStatusCode();
                    String code = se.getErrorCode();
                    return status >= 500
                            || "SYSTEMERROR".equalsIgnoreCase(code)
                            || "BANKERROR".equalsIgnoreCase(code)
                            || "USERPAYING".equalsIgnoreCase(code);
                }
                return false;
            })
            .build();

    /**
     * 商户服务持有者缓存：商户配置键/商户号 -> WechatServiceHolder
     */
    private final ConcurrentHashMap<String, WechatServiceHolder> holderCache = new ConcurrentHashMap<>();

    /**
     * 自定义 HTTP 客户端（用于单元测试 MockWebServer 拦截或生产定制代理）
     */
    private volatile HttpClient customHttpClient;

    public WechatPayTemplate(NamedMerchantRegistry namedMerchantRegistry,
                             WechatAutoCertificateManager wechatAutoCertificateManager) {
        this(namedMerchantRegistry, wechatAutoCertificateManager, new PayProperties());
    }

    public WechatPayTemplate(NamedMerchantRegistry namedMerchantRegistry,
                             WechatAutoCertificateManager wechatAutoCertificateManager,
                             PayProperties payProperties) {
        this.namedMerchantRegistry = Objects.requireNonNull(namedMerchantRegistry, "NamedMerchantRegistry 不能为空");
        this.wechatAutoCertificateManager = Objects.requireNonNull(wechatAutoCertificateManager, "WechatAutoCertificateManager 不能为空");
        this.payProperties = payProperties != null ? payProperties : new PayProperties();
    }

    /**
     * 动态配置资金分账总开关（主要用于单测切换或受控管理）
     *
     * @param enabled 是否开启分账功能
     */
    public void setProfitSharingEnabled(boolean enabled) {
        if (this.payProperties.getProfitSharing() == null) {
            this.payProperties.setProfitSharing(new PayProfitSharingProperties());
        }
        this.payProperties.getProfitSharing().setEnabled(enabled);
    }

    /**
     * 判断资金分账功能是否已开启
     *
     * @return true 开启，false 关闭
     */
    public boolean isProfitSharingEnabled() {
        return this.payProperties != null && this.payProperties.getProfitSharing() != null && this.payProperties.getProfitSharing().isEnabled();
    }

    /**
     * 资金分账物理总开关防卫前置断言
     */
    private void assertProfitSharingEnabled() {
        if (!isProfitSharingEnabled()) {
            throw new PayException(PayResultCode.PAY_FEATURE_DISABLED, "微信资金分账高敏特性未启用，请配置 ateng.boot.pay.profit-sharing.enabled=true 显式开启");
        }
    }

    /**
     * 动态设置单笔资金转账开关（主要供单测使用）
     */
    public void setTransferEnabled(boolean enabled) {
        if (this.payProperties.getTransfer() == null) {
            this.payProperties.setTransfer(new PayTransferProperties());
        }
        this.payProperties.getTransfer().setEnabled(enabled);
    }

    /**
     * 判断单笔资金转账功能是否已开启
     *
     * @return true 开启，false 关闭
     */
    public boolean isTransferEnabled() {
        return this.payProperties != null && this.payProperties.getTransfer() != null && this.payProperties.getTransfer().isEnabled();
    }

    /**
     * 单笔资金转账物理总开关防卫前置断言
     */
    private void assertTransferEnabled() {
        if (!isTransferEnabled()) {
            throw new PayException(PayResultCode.PAY_FEATURE_DISABLED, "微信资金转账高敏特性未启用，请配置 ateng.boot.pay.transfer.enabled=true 显式开启");
        }
    }

    /**
     * 设置自定义 HTTP 客户端（主要用于离线单测或定制网络代理）
     *
     * @param customHttpClient 自定义 HttpClient 实例
     */
    public void setCustomHttpClient(HttpClient customHttpClient) {
        this.customHttpClient = customHttpClient;
        this.holderCache.clear();
    }

    /**
     * 设置微信撤销冲正重试配置（主要用于单测毫秒级重试或高阶定制）
     *
     * @param wechatReverseRetryConfig 重试配置
     */
    public void setWechatReverseRetryConfig(RetryUtil.RetryConfig wechatReverseRetryConfig) {
        if (wechatReverseRetryConfig != null) {
            this.wechatReverseRetryConfig = wechatReverseRetryConfig;
        }
    }

    // =========================================================================
    // 1. Native 扫码支付 (生成二维码链接由用户扫描)
    // =========================================================================

    /**
     * 发起微信 Native 扫码收单
     *
     * @param request 微信收单请求参数
     * @return 统一收单响应对象（qrCode 挂载 code_url 二维码链接）
     */
    public PayResponse payNative(WechatPayRequest request) {
        validateCheckoutRequest(request);
        WechatMerchantProperties merchantProps = resolveMerchant(request);
        WechatServiceHolder holder = getServiceHolder(merchantProps);
        boolean isPartner = isPartnerMode(merchantProps, request);
        long fen = CurrencyUtil.yuanToFen(request.getAmount());
        String notifyUrl = resolveNotifyUrl(merchantProps, request);

        return executeWithExceptionTranslation("payNative", () -> {
            if (isPartner) {
                com.wechat.pay.java.service.partnerpayments.nativepay.model.PrepayRequest prepayReq =
                        new com.wechat.pay.java.service.partnerpayments.nativepay.model.PrepayRequest();
                prepayReq.setSpAppid(resolveSpAppId(merchantProps, request));
                prepayReq.setSpMchid(resolveSpMchId(merchantProps, request));
                prepayReq.setSubMchid(resolveSubMchId(merchantProps, request));
                if (resolveSubAppId(merchantProps, request) != null) {
                    prepayReq.setSubAppid(resolveSubAppId(merchantProps, request));
                }
                prepayReq.setDescription(request.getDescription());
                prepayReq.setOutTradeNo(request.getOutTradeNo());
                prepayReq.setTimeExpire(request.getTimeExpire());
                prepayReq.setAttach(request.getAttach());
                prepayReq.setNotifyUrl(notifyUrl);
                prepayReq.setGoodsTag(request.getGoodsTag());

                com.wechat.pay.java.service.partnerpayments.nativepay.model.Amount amount =
                        new com.wechat.pay.java.service.partnerpayments.nativepay.model.Amount();
                amount.setTotal((int) fen);
                amount.setCurrency(request.getCurrency() != null ? request.getCurrency() : "CNY");
                prepayReq.setAmount(amount);

                if (Boolean.TRUE.equals(request.getProfitSharing())) {
                    com.wechat.pay.java.service.partnerpayments.nativepay.model.SettleInfo settleInfo =
                            new com.wechat.pay.java.service.partnerpayments.nativepay.model.SettleInfo();
                    settleInfo.setProfitSharing(true);
                    prepayReq.setSettleInfo(settleInfo);
                }

                com.wechat.pay.java.service.partnerpayments.nativepay.model.PrepayResponse rawResp =
                        holder.getPartnerNativePayService().prepay(prepayReq);

                return PayResponse.builder()
                        .channel(PayChannel.WECHAT_PAY)
                        .tradeType(PayTradeType.NATIVE)
                        .outTradeNo(request.getOutTradeNo())
                        .amount(request.getAmount())
                        .qrCode(rawResp.getCodeUrl())
                        .tradeState(PayTradeState.NOTPAY)
                        .rawTradeState(PayTradeState.NOTPAY.getCode())
                        .tradeStateDesc("等待用户扫码支付")
                        .success(true)
                        .rawResponse(rawResp)
                        .build();
            } else {
                com.wechat.pay.java.service.payments.nativepay.model.PrepayRequest prepayReq =
                        new com.wechat.pay.java.service.payments.nativepay.model.PrepayRequest();
                prepayReq.setAppid(resolveAppId(merchantProps, request));
                prepayReq.setMchid(merchantProps.getMchId());
                prepayReq.setDescription(request.getDescription());
                prepayReq.setOutTradeNo(request.getOutTradeNo());
                prepayReq.setTimeExpire(request.getTimeExpire());
                prepayReq.setAttach(request.getAttach());
                prepayReq.setNotifyUrl(notifyUrl);
                prepayReq.setGoodsTag(request.getGoodsTag());

                com.wechat.pay.java.service.payments.nativepay.model.Amount amount =
                        new com.wechat.pay.java.service.payments.nativepay.model.Amount();
                amount.setTotal((int) fen);
                amount.setCurrency(request.getCurrency() != null ? request.getCurrency() : "CNY");
                prepayReq.setAmount(amount);

                if (Boolean.TRUE.equals(request.getProfitSharing())) {
                    com.wechat.pay.java.service.payments.nativepay.model.SettleInfo settleInfo =
                            new com.wechat.pay.java.service.payments.nativepay.model.SettleInfo();
                    settleInfo.setProfitSharing(true);
                    prepayReq.setSettleInfo(settleInfo);
                }

                com.wechat.pay.java.service.payments.nativepay.model.PrepayResponse rawResp =
                        holder.getNativePayService().prepay(prepayReq);

                return PayResponse.builder()
                        .channel(PayChannel.WECHAT_PAY)
                        .tradeType(PayTradeType.NATIVE)
                        .outTradeNo(request.getOutTradeNo())
                        .amount(request.getAmount())
                        .qrCode(rawResp.getCodeUrl())
                        .tradeState(PayTradeState.NOTPAY)
                        .rawTradeState(PayTradeState.NOTPAY.getCode())
                        .tradeStateDesc("等待用户扫码支付")
                        .success(true)
                        .rawResponse(rawResp)
                        .build();
            }
        });
    }

    // =========================================================================
    // 2. JSAPI 支付 (微信小程序 / 公众号网页，全自动服务端二次加签)
    // =========================================================================

    /**
     * 发起微信 JSAPI / 小程序收单，并在服务端自动完成二次签名与 5 元素封包
     *
     * @param request 微信收单请求参数
     * @return 统一收单响应对象（wechatJsapi 挂载可直接透传给 wx.requestPayment 的完整加签对象）
     */
    public PayResponse payJsapi(WechatPayRequest request) {
        validateCheckoutRequest(request);
        WechatMerchantProperties merchantProps = resolveMerchant(request);
        WechatServiceHolder holder = getServiceHolder(merchantProps);
        boolean isPartner = isPartnerMode(merchantProps, request);
        long fen = CurrencyUtil.yuanToFen(request.getAmount());
        String notifyUrl = resolveNotifyUrl(merchantProps, request);

        if (!isPartner && (request.getOpenid() == null || request.getOpenid().isBlank())) {
            throw new PayException(PayResultCode.PAY_PARAM_INVALID, "微信直连商户 JSAPI 支付必须指定用户 [openid]");
        }
        if (isPartner && (request.getSubOpenid() == null || request.getSubOpenid().isBlank())
                && (request.getOpenid() == null || request.getOpenid().isBlank())) {
            throw new PayException(PayResultCode.PAY_PARAM_INVALID, "微信服务商 JSAPI 支付必须指定用户 [subOpenid] 或 [openid]");
        }

        return executeWithExceptionTranslation("payJsapi", () -> {
            if (isPartner) {
                com.wechat.pay.java.service.partnerpayments.jsapi.model.PrepayRequest prepayReq =
                        new com.wechat.pay.java.service.partnerpayments.jsapi.model.PrepayRequest();
                String spAppId = resolveSpAppId(merchantProps, request);
                String subAppId = resolveSubAppId(merchantProps, request);
                prepayReq.setSpAppid(spAppId);
                prepayReq.setSpMchid(resolveSpMchId(merchantProps, request));
                prepayReq.setSubMchid(resolveSubMchId(merchantProps, request));
                if (subAppId != null) {
                    prepayReq.setSubAppid(subAppId);
                }
                prepayReq.setDescription(request.getDescription());
                prepayReq.setOutTradeNo(request.getOutTradeNo());
                prepayReq.setTimeExpire(request.getTimeExpire());
                prepayReq.setAttach(request.getAttach());
                prepayReq.setNotifyUrl(notifyUrl);
                prepayReq.setGoodsTag(request.getGoodsTag());

                com.wechat.pay.java.service.partnerpayments.jsapi.model.Amount amount =
                        new com.wechat.pay.java.service.partnerpayments.jsapi.model.Amount();
                amount.setTotal((int) fen);
                amount.setCurrency(request.getCurrency() != null ? request.getCurrency() : "CNY");
                prepayReq.setAmount(amount);

                com.wechat.pay.java.service.partnerpayments.jsapi.model.Payer payer =
                        new com.wechat.pay.java.service.partnerpayments.jsapi.model.Payer();
                if (request.getSubOpenid() != null && !request.getSubOpenid().isBlank()) {
                    payer.setSubOpenid(request.getSubOpenid());
                } else {
                    payer.setSpOpenid(request.getOpenid());
                }
                prepayReq.setPayer(payer);

                if (Boolean.TRUE.equals(request.getProfitSharing())) {
                    com.wechat.pay.java.service.partnerpayments.jsapi.model.SettleInfo settleInfo =
                            new com.wechat.pay.java.service.partnerpayments.jsapi.model.SettleInfo();
                    settleInfo.setProfitSharing(true);
                    prepayReq.setSettleInfo(settleInfo);
                }

                // 服务商二次调起签名 AppId：优先使用子商户 AppId，无则使用服务商 AppId
                String signAppId = subAppId != null ? subAppId : spAppId;
                com.wechat.pay.java.service.partnerpayments.jsapi.model.PrepayWithRequestPaymentResponse rawResp =
                        holder.getPartnerJsapiServiceExtension().prepayWithRequestPayment(prepayReq, signAppId);

                WechatJsapiParams jsapiParams = WechatJsapiParams.builder()
                        .appId(rawResp.getAppId())
                        .timeStamp(rawResp.getTimeStamp())
                        .nonceStr(rawResp.getNonceStr())
                        .packageVal(rawResp.getPackageVal())
                        .signType(rawResp.getSignType())
                        .paySign(rawResp.getPaySign())
                        .build();

                String prepayId = extractPrepayId(rawResp.getPackageVal());

                return PayResponse.builder()
                        .channel(PayChannel.WECHAT_PAY)
                        .tradeType(PayTradeType.JSAPI)
                        .outTradeNo(request.getOutTradeNo())
                        .amount(request.getAmount())
                        .prepayId(prepayId)
                        .wechatJsapi(jsapiParams)
                        .tradeState(PayTradeState.NOTPAY)
                        .rawTradeState(PayTradeState.NOTPAY.getCode())
                        .tradeStateDesc("等待用户在微信端确认支付")
                        .success(true)
                        .rawResponse(rawResp)
                        .build();
            } else {
                com.wechat.pay.java.service.payments.jsapi.model.PrepayRequest prepayReq =
                        new com.wechat.pay.java.service.payments.jsapi.model.PrepayRequest();
                String appId = resolveAppId(merchantProps, request);
                prepayReq.setAppid(appId);
                prepayReq.setMchid(merchantProps.getMchId());
                prepayReq.setDescription(request.getDescription());
                prepayReq.setOutTradeNo(request.getOutTradeNo());
                prepayReq.setTimeExpire(request.getTimeExpire());
                prepayReq.setAttach(request.getAttach());
                prepayReq.setNotifyUrl(notifyUrl);
                prepayReq.setGoodsTag(request.getGoodsTag());

                com.wechat.pay.java.service.payments.jsapi.model.Amount amount =
                        new com.wechat.pay.java.service.payments.jsapi.model.Amount();
                amount.setTotal((int) fen);
                amount.setCurrency(request.getCurrency() != null ? request.getCurrency() : "CNY");
                prepayReq.setAmount(amount);

                Payer payer = new Payer();
                payer.setOpenid(request.getOpenid());
                prepayReq.setPayer(payer);

                if (Boolean.TRUE.equals(request.getProfitSharing())) {
                    com.wechat.pay.java.service.payments.jsapi.model.SettleInfo settleInfo =
                            new com.wechat.pay.java.service.payments.jsapi.model.SettleInfo();
                    settleInfo.setProfitSharing(true);
                    prepayReq.setSettleInfo(settleInfo);
                }

                PrepayWithRequestPaymentResponse rawResp =
                        holder.getJsapiServiceExtension().prepayWithRequestPayment(prepayReq);

                WechatJsapiParams jsapiParams = WechatJsapiParams.builder()
                        .appId(rawResp.getAppId())
                        .timeStamp(rawResp.getTimeStamp())
                        .nonceStr(rawResp.getNonceStr())
                        .packageVal(rawResp.getPackageVal())
                        .signType(rawResp.getSignType())
                        .paySign(rawResp.getPaySign())
                        .build();

                String prepayId = extractPrepayId(rawResp.getPackageVal());

                return PayResponse.builder()
                        .channel(PayChannel.WECHAT_PAY)
                        .tradeType(PayTradeType.JSAPI)
                        .outTradeNo(request.getOutTradeNo())
                        .amount(request.getAmount())
                        .prepayId(prepayId)
                        .wechatJsapi(jsapiParams)
                        .tradeState(PayTradeState.NOTPAY)
                        .rawTradeState(PayTradeState.NOTPAY.getCode())
                        .tradeStateDesc("等待用户在微信端确认支付")
                        .success(true)
                        .rawResponse(rawResp)
                        .build();
            }
        });
    }

    // =========================================================================
    // 3. H5 手机网页跳转支付
    // =========================================================================

    /**
     * 发起微信 H5 手机网页收单
     *
     * @param request 微信收单请求参数
     * @return 统一收单响应对象（payUrl 挂载 h5_url 跳转链接）
     */
    public PayResponse payH5(WechatPayRequest request) {
        validateCheckoutRequest(request);
        WechatMerchantProperties merchantProps = resolveMerchant(request);
        WechatServiceHolder holder = getServiceHolder(merchantProps);
        boolean isPartner = isPartnerMode(merchantProps, request);
        long fen = CurrencyUtil.yuanToFen(request.getAmount());
        String notifyUrl = resolveNotifyUrl(merchantProps, request);

        return executeWithExceptionTranslation("payH5", () -> {
            if (isPartner) {
                com.wechat.pay.java.service.partnerpayments.h5.model.PrepayRequest prepayReq =
                        new com.wechat.pay.java.service.partnerpayments.h5.model.PrepayRequest();
                prepayReq.setSpAppid(resolveSpAppId(merchantProps, request));
                prepayReq.setSpMchid(resolveSpMchId(merchantProps, request));
                prepayReq.setSubMchid(resolveSubMchId(merchantProps, request));
                if (resolveSubAppId(merchantProps, request) != null) {
                    prepayReq.setSubAppid(resolveSubAppId(merchantProps, request));
                }
                prepayReq.setDescription(request.getDescription());
                prepayReq.setOutTradeNo(request.getOutTradeNo());
                prepayReq.setTimeExpire(request.getTimeExpire());
                prepayReq.setAttach(request.getAttach());
                prepayReq.setNotifyUrl(notifyUrl);
                prepayReq.setGoodsTag(request.getGoodsTag());

                com.wechat.pay.java.service.partnerpayments.h5.model.Amount amount =
                        new com.wechat.pay.java.service.partnerpayments.h5.model.Amount();
                amount.setTotal((int) fen);
                amount.setCurrency(request.getCurrency() != null ? request.getCurrency() : "CNY");
                prepayReq.setAmount(amount);

                com.wechat.pay.java.service.partnerpayments.h5.model.SceneInfo sceneInfo =
                        new com.wechat.pay.java.service.partnerpayments.h5.model.SceneInfo();
                sceneInfo.setPayerClientIp(request.getPayerClientIp() != null ? request.getPayerClientIp() : "127.0.0.1");
                com.wechat.pay.java.service.partnerpayments.h5.model.H5Info h5Info =
                        new com.wechat.pay.java.service.partnerpayments.h5.model.H5Info();
                h5Info.setType(request.getH5Type() != null ? request.getH5Type() : "Wap");
                sceneInfo.setH5Info(h5Info);
                prepayReq.setSceneInfo(sceneInfo);

                if (Boolean.TRUE.equals(request.getProfitSharing())) {
                    com.wechat.pay.java.service.partnerpayments.h5.model.SettleInfo settleInfo =
                            new com.wechat.pay.java.service.partnerpayments.h5.model.SettleInfo();
                    settleInfo.setProfitSharing(true);
                    prepayReq.setSettleInfo(settleInfo);
                }

                com.wechat.pay.java.service.partnerpayments.h5.model.PrepayResponse rawResp =
                        holder.getPartnerH5Service().prepay(prepayReq);

                return PayResponse.builder()
                        .channel(PayChannel.WECHAT_PAY)
                        .tradeType(PayTradeType.H5)
                        .outTradeNo(request.getOutTradeNo())
                        .amount(request.getAmount())
                        .payUrl(rawResp.getH5Url())
                        .tradeState(PayTradeState.NOTPAY)
                        .rawTradeState(PayTradeState.NOTPAY.getCode())
                        .tradeStateDesc("等待用户在外部浏览器中跳转微信支付")
                        .success(true)
                        .rawResponse(rawResp)
                        .build();
            } else {
                com.wechat.pay.java.service.payments.h5.model.PrepayRequest prepayReq =
                        new com.wechat.pay.java.service.payments.h5.model.PrepayRequest();
                prepayReq.setAppid(resolveAppId(merchantProps, request));
                prepayReq.setMchid(merchantProps.getMchId());
                prepayReq.setDescription(request.getDescription());
                prepayReq.setOutTradeNo(request.getOutTradeNo());
                prepayReq.setTimeExpire(request.getTimeExpire());
                prepayReq.setAttach(request.getAttach());
                prepayReq.setNotifyUrl(notifyUrl);
                prepayReq.setGoodsTag(request.getGoodsTag());

                com.wechat.pay.java.service.payments.h5.model.Amount amount =
                        new com.wechat.pay.java.service.payments.h5.model.Amount();
                amount.setTotal((int) fen);
                amount.setCurrency(request.getCurrency() != null ? request.getCurrency() : "CNY");
                prepayReq.setAmount(amount);

                SceneInfo sceneInfo = new SceneInfo();
                sceneInfo.setPayerClientIp(request.getPayerClientIp() != null ? request.getPayerClientIp() : "127.0.0.1");
                H5Info h5Info = new H5Info();
                h5Info.setType(request.getH5Type() != null ? request.getH5Type() : "Wap");
                sceneInfo.setH5Info(h5Info);
                prepayReq.setSceneInfo(sceneInfo);

                if (Boolean.TRUE.equals(request.getProfitSharing())) {
                    com.wechat.pay.java.service.payments.h5.model.SettleInfo settleInfo =
                            new com.wechat.pay.java.service.payments.h5.model.SettleInfo();
                    settleInfo.setProfitSharing(true);
                    prepayReq.setSettleInfo(settleInfo);
                }

                com.wechat.pay.java.service.payments.h5.model.PrepayResponse rawResp =
                        holder.getH5Service().prepay(prepayReq);

                return PayResponse.builder()
                        .channel(PayChannel.WECHAT_PAY)
                        .tradeType(PayTradeType.H5)
                        .outTradeNo(request.getOutTradeNo())
                        .amount(request.getAmount())
                        .payUrl(rawResp.getH5Url())
                        .tradeState(PayTradeState.NOTPAY)
                        .rawTradeState(PayTradeState.NOTPAY.getCode())
                        .tradeStateDesc("等待用户在外部浏览器中跳转微信支付")
                        .success(true)
                        .rawResponse(rawResp)
                        .build();
            }
        });
    }

    // =========================================================================
    // 4. App 客户端 SDK 调起支付
    // =========================================================================

    /**
     * 发起微信 App 客户端收单并在服务端全自动完成调起参数加签封包
     *
     * @param request 微信收单请求参数
     * @return 统一收单响应对象（wechatApp 挂载可直接传给 App 微信 SDK 的调起凭据）
     */
    public PayResponse payApp(WechatPayRequest request) {
        validateCheckoutRequest(request);
        WechatMerchantProperties merchantProps = resolveMerchant(request);
        WechatServiceHolder holder = getServiceHolder(merchantProps);
        boolean isPartner = isPartnerMode(merchantProps, request);
        long fen = CurrencyUtil.yuanToFen(request.getAmount());
        String notifyUrl = resolveNotifyUrl(merchantProps, request);

        return executeWithExceptionTranslation("payApp", () -> {
            if (isPartner) {
                com.wechat.pay.java.service.partnerpayments.app.model.PrepayRequest prepayReq =
                        new com.wechat.pay.java.service.partnerpayments.app.model.PrepayRequest();
                String spAppId = resolveSpAppId(merchantProps, request);
                String subAppId = resolveSubAppId(merchantProps, request);
                prepayReq.setSpAppid(spAppId);
                prepayReq.setSpMchid(resolveSpMchId(merchantProps, request));
                prepayReq.setSubMchid(resolveSubMchId(merchantProps, request));
                if (subAppId != null) {
                    prepayReq.setSubAppid(subAppId);
                }
                prepayReq.setDescription(request.getDescription());
                prepayReq.setOutTradeNo(request.getOutTradeNo());
                prepayReq.setTimeExpire(request.getTimeExpire());
                prepayReq.setAttach(request.getAttach());
                prepayReq.setNotifyUrl(notifyUrl);
                prepayReq.setGoodsTag(request.getGoodsTag());

                com.wechat.pay.java.service.partnerpayments.app.model.Amount amount =
                        new com.wechat.pay.java.service.partnerpayments.app.model.Amount();
                amount.setTotal((int) fen);
                amount.setCurrency(request.getCurrency() != null ? request.getCurrency() : "CNY");
                prepayReq.setAmount(amount);

                if (Boolean.TRUE.equals(request.getProfitSharing())) {
                    com.wechat.pay.java.service.partnerpayments.app.model.SettleInfo settleInfo =
                            new com.wechat.pay.java.service.partnerpayments.app.model.SettleInfo();
                    settleInfo.setProfitSharing(true);
                    prepayReq.setSettleInfo(settleInfo);
                }

                String signAppId = subAppId != null ? subAppId : spAppId;
                com.wechat.pay.java.service.partnerpayments.app.model.PrepayWithRequestPaymentResponse rawResp =
                        holder.getPartnerAppServiceExtension().prepayWithRequestPayment(prepayReq, signAppId);

                WechatAppParams appParams = WechatAppParams.builder()
                        .appid(rawResp.getAppid())
                        .partnerid(rawResp.getPartnerId())
                        .prepayid(rawResp.getPrepayId())
                        .packageVal(rawResp.getPackageVal())
                        .noncestr(rawResp.getNonceStr())
                        .timestamp(rawResp.getTimestamp())
                        .sign(rawResp.getSign())
                        .build();

                return PayResponse.builder()
                        .channel(PayChannel.WECHAT_PAY)
                        .tradeType(PayTradeType.APP)
                        .outTradeNo(request.getOutTradeNo())
                        .amount(request.getAmount())
                        .prepayId(rawResp.getPrepayId())
                        .orderString(rawResp.getPrepayId())
                        .wechatApp(appParams)
                        .tradeState(PayTradeState.NOTPAY)
                        .rawTradeState(PayTradeState.NOTPAY.getCode())
                        .tradeStateDesc("等待用户在 App 中拉起微信完成支付")
                        .success(true)
                        .rawResponse(rawResp)
                        .build();
            } else {
                com.wechat.pay.java.service.payments.app.model.PrepayRequest prepayReq =
                        new com.wechat.pay.java.service.payments.app.model.PrepayRequest();
                prepayReq.setAppid(resolveAppId(merchantProps, request));
                prepayReq.setMchid(merchantProps.getMchId());
                prepayReq.setDescription(request.getDescription());
                prepayReq.setOutTradeNo(request.getOutTradeNo());
                prepayReq.setTimeExpire(request.getTimeExpire());
                prepayReq.setAttach(request.getAttach());
                prepayReq.setNotifyUrl(notifyUrl);
                prepayReq.setGoodsTag(request.getGoodsTag());

                com.wechat.pay.java.service.payments.app.model.Amount amount =
                        new com.wechat.pay.java.service.payments.app.model.Amount();
                amount.setTotal((int) fen);
                amount.setCurrency(request.getCurrency() != null ? request.getCurrency() : "CNY");
                prepayReq.setAmount(amount);

                if (Boolean.TRUE.equals(request.getProfitSharing())) {
                    com.wechat.pay.java.service.payments.app.model.SettleInfo settleInfo =
                            new com.wechat.pay.java.service.payments.app.model.SettleInfo();
                    settleInfo.setProfitSharing(true);
                    prepayReq.setSettleInfo(settleInfo);
                }

                com.wechat.pay.java.service.payments.app.model.PrepayWithRequestPaymentResponse rawResp =
                        holder.getAppServiceExtension().prepayWithRequestPayment(prepayReq);

                WechatAppParams appParams = WechatAppParams.builder()
                        .appid(rawResp.getAppid())
                        .partnerid(rawResp.getPartnerId())
                        .prepayid(rawResp.getPrepayId())
                        .packageVal(rawResp.getPackageVal())
                        .noncestr(rawResp.getNonceStr())
                        .timestamp(rawResp.getTimestamp())
                        .sign(rawResp.getSign())
                        .build();

                return PayResponse.builder()
                        .channel(PayChannel.WECHAT_PAY)
                        .tradeType(PayTradeType.APP)
                        .outTradeNo(request.getOutTradeNo())
                        .amount(request.getAmount())
                        .prepayId(rawResp.getPrepayId())
                        .orderString(rawResp.getPrepayId())
                        .wechatApp(appParams)
                        .tradeState(PayTradeState.NOTPAY)
                        .rawTradeState(PayTradeState.NOTPAY.getCode())
                        .tradeStateDesc("等待用户在 App 中拉起微信完成支付")
                        .success(true)
                        .rawResponse(rawResp)
                        .build();
            }
        });
    }

    // =========================================================================
    // 5. 条码支付 / 当面付 (商户扫描用户付款码，MicroPay)
    // =========================================================================

    /**
     * 发起微信条码支付（商户扫描用户微信付款码）
     *
     * @param request 微信条码收单请求参数
     * @return 统一收单响应对象（可能直接返回 SUCCESS 或 USERPAYING 等待用户输入密码）
     */
    public PayResponse payBarcode(WechatPayRequest request) {
        validateCheckoutRequest(request);
        if (request.getAuthCode() == null || request.getAuthCode().isBlank()) {
            throw new PayException(PayResultCode.PAY_PARAM_INVALID, "条码付收单必须提供用户付款码 [authCode]");
        }
        WechatMerchantProperties merchantProps = resolveMerchant(request);
        WechatServiceHolder holder = getServiceHolder(merchantProps);
        boolean isPartner = isPartnerMode(merchantProps, request);
        long fen = CurrencyUtil.yuanToFen(request.getAmount());

        return executeWithExceptionTranslation("payBarcode", () -> {
            String url = isPartner ? PARTNER_CODEPAY_URL : DIRECT_CODEPAY_URL;

            Map<String, Object> bodyMap = new LinkedHashMap<>();
            if (isPartner) {
                bodyMap.put("sp_appid", resolveSpAppId(merchantProps, request));
                bodyMap.put("sp_mchid", resolveSpMchId(merchantProps, request));
                bodyMap.put("sub_mchid", resolveSubMchId(merchantProps, request));
                if (resolveSubAppId(merchantProps, request) != null) {
                    bodyMap.put("sub_appid", resolveSubAppId(merchantProps, request));
                }
            } else {
                bodyMap.put("appid", resolveAppId(merchantProps, request));
                bodyMap.put("mchid", merchantProps.getMchId());
            }
            bodyMap.put("description", request.getDescription());
            bodyMap.put("out_trade_no", request.getOutTradeNo());
            if (request.getTimeExpire() != null) {
                bodyMap.put("time_expire", request.getTimeExpire());
            }
            if (request.getAttach() != null) {
                bodyMap.put("attach", request.getAttach());
            }
            if (request.getGoodsTag() != null) {
                bodyMap.put("goods_tag", request.getGoodsTag());
            }
            bodyMap.put("amount", Map.of("total", fen, "currency", request.getCurrency() != null ? request.getCurrency() : "CNY"));
            bodyMap.put("payer", Map.of("auth_code", request.getAuthCode()));
            if (Boolean.TRUE.equals(request.getProfitSharing())) {
                bodyMap.put("settle_info", Map.of("profit_sharing", true));
            }

            String jsonBody = JsonUtil.toJsonString(bodyMap);

            HttpRequest httpRequest = new HttpRequest.Builder()
                    .httpMethod(HttpMethod.POST)
                    .url(url)
                    .addHeader("Accept", "application/json")
                    .addHeader("Content-Type", "application/json")
                    .body(new JsonRequestBody.Builder().body(jsonBody).build())
                    .build();

            HttpResponse<Transaction> httpResp = holder.getHttpClient().execute(httpRequest, Transaction.class);
            Transaction transaction = httpResp.getServiceResponse();

            return toPayResponse(transaction, request, PayTradeType.BARCODE);
        });
    }

    // =========================================================================
    // 6. 订单状态查询 (queryOrder)
    // =========================================================================

    /**
     * 查询微信支付订单状态
     *
     * @param request 查询请求参数（支持指定 outTradeNo 或 transactionId）
     * @return 统一交易查询结果响应
     */
    public PayResponse queryOrder(WechatPayRequest request) {
        if ((request.getOutTradeNo() == null || request.getOutTradeNo().isBlank())
                && (request.getTransactionId() == null || request.getTransactionId().isBlank())) {
            throw new PayException(PayResultCode.PAY_PARAM_INVALID, "查询订单必须提供 [outTradeNo] 或 [transactionId]");
        }
        WechatMerchantProperties merchantProps = resolveMerchant(request);
        WechatServiceHolder holder = getServiceHolder(merchantProps);
        boolean isPartner = isPartnerMode(merchantProps, request);

        return executeWithExceptionTranslation("queryOrder", () -> {
            if (isPartner) {
                com.wechat.pay.java.service.partnerpayments.jsapi.model.Transaction transaction;
                if (request.getTransactionId() != null && !request.getTransactionId().isBlank()) {
                    com.wechat.pay.java.service.partnerpayments.jsapi.model.QueryOrderByIdRequest req =
                            new com.wechat.pay.java.service.partnerpayments.jsapi.model.QueryOrderByIdRequest();
                    req.setTransactionId(request.getTransactionId());
                    req.setSpMchid(resolveSpMchId(merchantProps, request));
                    req.setSubMchid(resolveSubMchId(merchantProps, request));
                    transaction = holder.getPartnerJsapiServiceExtension().queryOrderById(req);
                } else {
                    com.wechat.pay.java.service.partnerpayments.jsapi.model.QueryOrderByOutTradeNoRequest req =
                            new com.wechat.pay.java.service.partnerpayments.jsapi.model.QueryOrderByOutTradeNoRequest();
                    req.setOutTradeNo(request.getOutTradeNo());
                    req.setSpMchid(resolveSpMchId(merchantProps, request));
                    req.setSubMchid(resolveSubMchId(merchantProps, request));
                    transaction = holder.getPartnerJsapiServiceExtension().queryOrderByOutTradeNo(req);
                }
                return toPayResponse(transaction, request, null);
            } else {
                Transaction transaction;
                if (request.getTransactionId() != null && !request.getTransactionId().isBlank()) {
                    QueryOrderByIdRequest req = new QueryOrderByIdRequest();
                    req.setTransactionId(request.getTransactionId());
                    req.setMchid(merchantProps.getMchId());
                    transaction = holder.getJsapiServiceExtension().queryOrderById(req);
                } else {
                    QueryOrderByOutTradeNoRequest req = new QueryOrderByOutTradeNoRequest();
                    req.setOutTradeNo(request.getOutTradeNo());
                    req.setMchid(merchantProps.getMchId());
                    transaction = holder.getJsapiServiceExtension().queryOrderByOutTradeNo(req);
                }
                return toPayResponse(transaction, request, null);
            }
        });
    }

    /**
     * 单标识符重载：根据商户订单号查询默认商户订单
     *
     * @param outTradeNo 商户订单号
     * @return 统一交易查询结果响应
     */
    public PayResponse queryOrder(String outTradeNo) {
        return queryOrder(WechatPayRequest.builder().outTradeNo(outTradeNo).build());
    }

    /**
     * 单标识符重载：指定商户号/商户别名查询订单
     *
     * @param merchantKey 商户别名或商户号
     * @param outTradeNo  商户订单号
     * @return 统一交易查询结果响应
     */
    public PayResponse queryOrder(String merchantKey, String outTradeNo) {
        return queryOrder(WechatPayRequest.builder().merchantKey(merchantKey).outTradeNo(outTradeNo).build());
    }

    // =========================================================================
    // 7. 关闭订单 (closeOrder)
    // =========================================================================

    /**
     * 关闭未付款的微信支付订单
     *
     * @param request 关闭请求参数（必须提供 outTradeNo）
     * @return 关闭操作响应
     */
    public PayResponse closeOrder(WechatPayRequest request) {
        if (request.getOutTradeNo() == null || request.getOutTradeNo().isBlank()) {
            throw new PayException(PayResultCode.PAY_PARAM_INVALID, "关闭订单必须提供商户订单号 [outTradeNo]");
        }
        WechatMerchantProperties merchantProps = resolveMerchant(request);
        WechatServiceHolder holder = getServiceHolder(merchantProps);
        boolean isPartner = isPartnerMode(merchantProps, request);

        return executeWithExceptionTranslation("closeOrder", () -> {
            if (isPartner) {
                com.wechat.pay.java.service.partnerpayments.jsapi.model.CloseOrderRequest req =
                        new com.wechat.pay.java.service.partnerpayments.jsapi.model.CloseOrderRequest();
                req.setOutTradeNo(request.getOutTradeNo());
                req.setSpMchid(resolveSpMchId(merchantProps, request));
                req.setSubMchid(resolveSubMchId(merchantProps, request));
                holder.getPartnerJsapiServiceExtension().closeOrder(req);
            } else {
                CloseOrderRequest req = new CloseOrderRequest();
                req.setOutTradeNo(request.getOutTradeNo());
                req.setMchid(merchantProps.getMchId());
                holder.getJsapiServiceExtension().closeOrder(req);
            }

            return PayResponse.builder()
                    .channel(PayChannel.WECHAT_PAY)
                    .outTradeNo(request.getOutTradeNo())
                    .tradeState(PayTradeState.CLOSED)
                    .rawTradeState(PayTradeState.CLOSED.getCode())
                    .tradeStateDesc("订单已成功关闭")
                    .success(true)
                    .build();
        });
    }

    /**
     * 单标识符重载：关闭当前默认商户的未付款订单
     *
     * @param outTradeNo 商户订单号
     * @return 关闭操作响应
     */
    public PayResponse closeOrder(String outTradeNo) {
        return closeOrder(WechatPayRequest.builder().outTradeNo(outTradeNo).build());
    }

    /**
     * 单标识符重载：指定商户号/商户别名关闭未付款订单
     *
     * @param merchantKey 商户别名或商户号
     * @param outTradeNo  商户订单号
     * @return 关闭操作响应
     */
    public PayResponse closeOrder(String merchantKey, String outTradeNo) {
        return closeOrder(WechatPayRequest.builder().merchantKey(merchantKey).outTradeNo(outTradeNo).build());
    }

    // =========================================================================
    // 7.1 撤销/冲正订单 (cancelOrder / reverse)
    // =========================================================================

    /**
     * 撤销/冲正微信支付订单（主要用于线下条码付等场景超时未决时的最终处置）
     * <p>
     * 微信官方说明：支付规则为若用户未支付则关闭订单，若用户已支付则自动原路退款。
     * 当遇到网络通信抖动或微信系统繁忙（SYSTEMERROR/BANKERROR）时，内置依托 RetryUtil 最多重试 3 次（固定间隔 3 秒）。
     * </p>
     *
     * @param request 撤销请求参数（必须提供 outTradeNo）
     * @return 撤销冲正操作响应（tradeState 为 CLOSED）
     */
    public PayResponse cancelOrder(WechatPayRequest request) {
        if (request == null || request.getOutTradeNo() == null || request.getOutTradeNo().isBlank()) {
            throw new PayException(PayResultCode.PAY_PARAM_INVALID, "撤销订单必须提供商户订单号 [outTradeNo]");
        }
        WechatMerchantProperties merchantProps = resolveMerchant(request);
        WechatServiceHolder holder = getServiceHolder(merchantProps);
        boolean isPartner = isPartnerMode(merchantProps, request);

        return executeWithExceptionTranslation("cancelOrder", () -> {
            String url = isPartner
                    ? String.format(PARTNER_REVERSE_URL, request.getOutTradeNo())
                    : String.format(DIRECT_REVERSE_URL, request.getOutTradeNo());

            Map<String, Object> bodyMap = new LinkedHashMap<>();
            if (isPartner) {
                bodyMap.put("sp_mchid", resolveSpMchId(merchantProps, request));
                bodyMap.put("sub_mchid", resolveSubMchId(merchantProps, request));
            } else {
                bodyMap.put("mchid", merchantProps.getMchId());
            }

            String jsonBody = JsonUtil.toJsonString(bodyMap);

            HttpRequest httpRequest = new HttpRequest.Builder()
                    .httpMethod(HttpMethod.POST)
                    .url(url)
                    .addHeader("Accept", "application/json")
                    .addHeader("Content-Type", "application/json")
                    .body(new JsonRequestBody.Builder().body(jsonBody).build())
                    .build();

            // 依托 Core 原语 RetryUtil 执行受控退避重试（默认最多 3 次，间隔 3 秒）
            RetryUtil.execute(() -> {
                holder.getHttpClient().execute(httpRequest, Object.class);
            }, wechatReverseRetryConfig);

            return PayResponse.builder()
                    .channel(PayChannel.WECHAT_PAY)
                    .outTradeNo(request.getOutTradeNo())
                    .tradeState(PayTradeState.CLOSED)
                    .rawTradeState(PayTradeState.CLOSED.getCode())
                    .tradeStateDesc("订单已成功撤销冲正")
                    .success(true)
                    .build();
        });
    }

    /**
     * 单标识符重载：撤销当前默认商户的订单
     *
     * @param outTradeNo 商户订单号
     * @return 撤销冲正操作响应
     */
    public PayResponse cancelOrder(String outTradeNo) {
        return cancelOrder(WechatPayRequest.builder().outTradeNo(outTradeNo).build());
    }

    /**
     * 单标识符重载：指定商户号/商户别名撤销订单
     *
     * @param merchantKey 商户别名或商户号
     * @param outTradeNo  商户订单号
     * @return 撤销冲正操作响应
     */
    public PayResponse cancelOrder(String merchantKey, String outTradeNo) {
        return cancelOrder(WechatPayRequest.builder().merchantKey(merchantKey).outTradeNo(outTradeNo).build());
    }

    // =========================================================================
    // 8. 申请退款 (refund)
    // =========================================================================

    /**
     * 发起微信退款申请（支持直连商户与服务商模式特约子商户，支持多次部分退款与全额退款）
     *
     * @param request 微信退款请求参数
     * @return 统一退款响应出参
     */
    public PayRefundResponse refund(WechatRefundRequest request) {
        validateRefundRequest(request);
        WechatMerchantProperties merchantProps = resolveMerchant(request.getMerchantKey());
        WechatServiceHolder holder = getServiceHolder(merchantProps);
        boolean isPartner = isPartnerMode(merchantProps, request.getSubMchId());
        long refundFen = CurrencyUtil.yuanToFen(request.getRefundAmount());
        long totalFen = CurrencyUtil.yuanToFen(request.getTotalAmount());
        String notifyUrl = request.getNotifyUrl() != null && !request.getNotifyUrl().isBlank()
                ? request.getNotifyUrl()
                : merchantProps.getNotifyUrl();

        return executeWithExceptionTranslation("refund", () -> {
            CreateRequest createReq = new CreateRequest();
            if (isPartner) {
                createReq.setSubMchid(resolveSubMchId(merchantProps, request.getSubMchId()));
            }
            if (request.getOutTradeNo() != null && !request.getOutTradeNo().isBlank()) {
                createReq.setOutTradeNo(request.getOutTradeNo());
            }
            if (request.getTransactionId() != null && !request.getTransactionId().isBlank()) {
                createReq.setTransactionId(request.getTransactionId());
            }
            createReq.setOutRefundNo(request.getOutRefundNo());
            if (request.getReason() != null && !request.getReason().isBlank()) {
                createReq.setReason(request.getReason());
            }
            if (notifyUrl != null && !notifyUrl.isBlank()) {
                createReq.setNotifyUrl(notifyUrl);
            }
            if (request.getFundsAccount() != null && !request.getFundsAccount().isBlank()) {
                try {
                    createReq.setFundsAccount(ReqFundsAccount.valueOf(request.getFundsAccount().toUpperCase()));
                } catch (IllegalArgumentException e) {
                    throw new PayException(PayResultCode.PAY_PARAM_INVALID,
                            "非法的微信退款出资账户资金类型: [" + request.getFundsAccount() + "]", e);
                }
            }

            AmountReq amountReq = new AmountReq();
            amountReq.setRefund(refundFen);
            amountReq.setTotal(totalFen);
            amountReq.setCurrency(request.getCurrency() != null ? request.getCurrency() : "CNY");
            createReq.setAmount(amountReq);

            Refund rawResp = holder.getRefundService().create(createReq);
            return toPayRefundResponse(rawResp, request.getTotalAmount(), request.getRefundAmount());
        });
    }

    /**
     * 基于顶层统一退款请求模型发起微信退款
     *
     * @param request 顶层统一退款请求入参
     * @return 统一退款响应出参
     */
    public PayRefundResponse refund(UnifiedRefundRequest request) {
        if (request == null) {
            throw new PayException(PayResultCode.PAY_PARAM_INVALID, "统一退款请求入参不能为空");
        }
        request.validate();
        return refund(WechatRefundRequest.builder()
                .merchantKey(request.getMerchantKey())
                .outTradeNo(request.getOutTradeNo())
                .transactionId(request.getTransactionId())
                .outRefundNo(request.getOutRefundNo())
                .totalAmount(request.getTotalAmount())
                .refundAmount(request.getRefundAmount())
                .reason(request.getReason())
                .notifyUrl(request.getNotifyUrl())
                .currency(request.getCurrency())
                .subMchId(request.getSubMchId())
                .build());
    }

    // =========================================================================
    // 9. 查询单笔退款 (queryRefund)
    // =========================================================================

    /**
     * 查询微信单笔退款状态（通过商户退款单号）
     *
     * @param request 微信退款查询请求
     * @return 统一退款响应出参
     */
    public PayRefundResponse queryRefund(WechatRefundRequest request) {
        if (request == null) {
            throw new PayException(PayResultCode.PAY_PARAM_INVALID, "微信退款查询请求入参不能为空");
        }
        if (request.getOutRefundNo() == null || request.getOutRefundNo().isBlank()) {
            throw new PayException(PayResultCode.PAY_PARAM_INVALID, "查询微信退款必须提供商户退款单号 [outRefundNo]");
        }
        WechatMerchantProperties merchantProps = resolveMerchant(request.getMerchantKey());
        WechatServiceHolder holder = getServiceHolder(merchantProps);
        boolean isPartner = isPartnerMode(merchantProps, request.getSubMchId());

        return executeWithExceptionTranslation("queryRefund", () -> {
            QueryByOutRefundNoRequest queryReq = new QueryByOutRefundNoRequest();
            queryReq.setOutRefundNo(request.getOutRefundNo());
            if (isPartner) {
                queryReq.setSubMchid(resolveSubMchId(merchantProps, request.getSubMchId()));
            }

            Refund rawResp = holder.getRefundService().queryByOutRefundNo(queryReq);
            return toPayRefundResponse(rawResp, request.getTotalAmount(), request.getRefundAmount());
        });
    }

    /**
     * 单标识符重载：查询当前默认商户的退款状态
     *
     * @param outRefundNo 商户退款单号
     * @return 统一退款响应出参
     */
    public PayRefundResponse queryRefund(String outRefundNo) {
        return queryRefund(WechatRefundRequest.builder().outRefundNo(outRefundNo).build());
    }

    /**
     * 单标识符重载：指定商户号/商户别名查询退款状态
     *
     * @param merchantKey 商户别名或商户号
     * @param outRefundNo 商户退款单号
     * @return 统一退款响应出参
     */
    public PayRefundResponse queryRefund(String merchantKey, String outRefundNo) {
        return queryRefund(WechatRefundRequest.builder().merchantKey(merchantKey).outRefundNo(outRefundNo).build());
    }

    // =========================================================================
    // 10. 状态机与模型映射辅助工具 (Normalization)
    // =========================================================================

    /**
     * 将微信原生退款状态映射为统一退款状态枚举
     *
     * @param wechatStatus 微信 SDK 原生枚举
     * @return 标准化统一退款状态
     */
    public static PayRefundState mapRefundState(Status wechatStatus) {
        if (wechatStatus == null) {
            return PayRefundState.PROCESSING;
        }
        return switch (wechatStatus) {
            case SUCCESS -> PayRefundState.SUCCESS;
            case PROCESSING -> PayRefundState.PROCESSING;
            case CLOSED -> PayRefundState.CLOSED;
            case ABNORMAL -> PayRefundState.ABNORMAL;
        };
    }

    /**
     * 将字符串退款状态码转换为统一退款状态枚举
     *
     * @param rawState 原始状态字符串
     * @return 标准化统一退款状态
     */
    public static PayRefundState mapRefundState(String rawState) {
        if (rawState == null || rawState.isBlank()) {
            return PayRefundState.PROCESSING;
        }
        try {
            return mapRefundState(Status.valueOf(rawState.toUpperCase()));
        } catch (IllegalArgumentException e) {
            return PayRefundState.ABNORMAL;
        }
    }

    /**
     * 将微信底层退款实体映射为统一 PayRefundResponse
     */
    private PayRefundResponse toPayRefundResponse(Refund rawResp, BigDecimal fallbackTotal, BigDecimal fallbackRefund) {
        if (rawResp == null) {
            return PayRefundResponse.builder()
                    .channel(PayChannel.WECHAT_PAY)
                    .refundState(PayRefundState.ABNORMAL)
                    .rawRefundState("EMPTY_RESPONSE")
                    .refundStateDesc("微信退款响应为空")
                    .success(false)
                    .build();
        }

        PayRefundState refundState = mapRefundState(rawResp.getStatus());
        BigDecimal total = fallbackTotal;
        BigDecimal refund = fallbackRefund;
        if (rawResp.getAmount() != null) {
            if (rawResp.getAmount().getTotal() != null) {
                total = CurrencyUtil.fenToYuan(rawResp.getAmount().getTotal());
            }
            if (rawResp.getAmount().getRefund() != null) {
                refund = CurrencyUtil.fenToYuan(rawResp.getAmount().getRefund());
            }
        }

        return PayRefundResponse.builder()
                .channel(PayChannel.WECHAT_PAY)
                .outTradeNo(rawResp.getOutTradeNo())
                .transactionId(rawResp.getTransactionId())
                .outRefundNo(rawResp.getOutRefundNo())
                .refundId(rawResp.getRefundId())
                .totalAmount(total)
                .refundAmount(refund)
                .userReceivedAccount(rawResp.getUserReceivedAccount())
                .successTime(rawResp.getSuccessTime())
                .refundState(refundState)
                .rawRefundState(rawResp.getStatus() != null ? rawResp.getStatus().name() : null)
                .refundStateDesc(rawResp.getStatus() != null ? rawResp.getStatus().name() : "退款处理中")
                .success(refundState == PayRefundState.SUCCESS || refundState == PayRefundState.PROCESSING)
                .rawResponse(rawResp)
                .build();
    }

    private void validateRefundRequest(WechatRefundRequest request) {
        if (request == null) {
            throw new PayException(PayResultCode.PAY_PARAM_INVALID, "微信退款请求入参不能为空");
        }
        request.validate();
    }

    /**
     * 将微信原生交易状态转换为统一交易状态枚举
     *
     * @param tradeStateEnum 微信 SDK 原生枚举
     * @return 标准化交易生命周期状态
     */
    public static PayTradeState mapTradeState(Transaction.TradeStateEnum tradeStateEnum) {
        if (tradeStateEnum == null) {
            return PayTradeState.NOTPAY;
        }
        return switch (tradeStateEnum) {
            case SUCCESS -> PayTradeState.SUCCESS;
            case REFUND -> PayTradeState.REFUND;
            case NOTPAY -> PayTradeState.NOTPAY;
            case CLOSED -> PayTradeState.CLOSED;
            case REVOKED -> PayTradeState.REVOKED;
            case USERPAYING, ACCEPT -> PayTradeState.USERPAYING;
            case PAYERROR -> PayTradeState.PAYERROR;
        };
    }

    /**
     * 将字符串交易状态码转换为统一交易状态枚举
     *
     * @param rawState 原始状态字符串
     * @return 标准化交易生命周期状态
     */
    public static PayTradeState mapTradeState(String rawState) {
        if (rawState == null || rawState.isBlank()) {
            return PayTradeState.NOTPAY;
        }
        try {
            return mapTradeState(Transaction.TradeStateEnum.valueOf(rawState.toUpperCase()));
        } catch (IllegalArgumentException e) {
            return PayTradeState.PAYERROR;
        }
    }

    /**
     * 将微信原生交易场景类型映射为统一场景枚举
     *
     * @param tradeTypeEnum 微信 SDK 原生交易类型
     * @return 标准化交易类型
     */
    public static PayTradeType mapTradeType(Transaction.TradeTypeEnum tradeTypeEnum) {
        if (tradeTypeEnum == null) {
            return null;
        }
        return switch (tradeTypeEnum) {
            case NATIVE -> PayTradeType.NATIVE;
            case JSAPI -> PayTradeType.JSAPI;
            case MWEB -> PayTradeType.H5;
            case APP -> PayTradeType.APP;
            case MICROPAY, FACEPAY -> PayTradeType.BARCODE;
        };
    }

    /**
     * 将微信底层 Transaction 实体映射为统一 PayResponse
     */
    private PayResponse toPayResponse(Transaction transaction, WechatPayRequest request, PayTradeType defaultTradeType) {
        if (transaction == null) {
            return PayResponse.builder()
                    .channel(PayChannel.WECHAT_PAY)
                    .tradeType(defaultTradeType)
                    .outTradeNo(request != null ? request.getOutTradeNo() : null)
                    .tradeState(PayTradeState.NOTPAY)
                    .success(false)
                    .build();
        }

        PayTradeState tradeState = mapTradeState(transaction.getTradeState());
        BigDecimal amount = null;
        if (transaction.getAmount() != null && transaction.getAmount().getTotal() != null) {
            amount = CurrencyUtil.fenToYuan(transaction.getAmount().getTotal().longValue());
        } else if (request != null && request.getAmount() != null) {
            amount = request.getAmount();
        }

        PayTradeType tradeType = mapTradeType(transaction.getTradeType());
        if (tradeType == null) {
            tradeType = defaultTradeType;
        }

        return PayResponse.builder()
                .channel(PayChannel.WECHAT_PAY)
                .tradeType(tradeType)
                .outTradeNo(transaction.getOutTradeNo() != null ? transaction.getOutTradeNo() : (request != null ? request.getOutTradeNo() : null))
                .transactionId(transaction.getTransactionId())
                .tradeState(tradeState)
                .rawTradeState(transaction.getTradeState() != null ? transaction.getTradeState().name() : null)
                .tradeStateDesc(transaction.getTradeStateDesc())
                .amount(amount)
                .success(tradeState.isSuccess())
                .rawResponse(transaction)
                .build();
    }

    /**
     * 将服务商底层 Transaction 实体映射为统一 PayResponse
     */
    public static PayTradeState mapTradeState(com.wechat.pay.java.service.partnerpayments.jsapi.model.Transaction.TradeStateEnum tradeStateEnum) {
        if (tradeStateEnum == null) {
            return PayTradeState.NOTPAY;
        }
        return switch (tradeStateEnum) {
            case SUCCESS -> PayTradeState.SUCCESS;
            case REFUND -> PayTradeState.REFUND;
            case NOTPAY -> PayTradeState.NOTPAY;
            case CLOSED -> PayTradeState.CLOSED;
            case REVOKED -> PayTradeState.REVOKED;
            case USERPAYING, ACCEPT -> PayTradeState.USERPAYING;
            case PAYERROR -> PayTradeState.PAYERROR;
        };
    }

    public static PayTradeType mapTradeType(com.wechat.pay.java.service.partnerpayments.jsapi.model.Transaction.TradeTypeEnum tradeTypeEnum) {
        if (tradeTypeEnum == null) {
            return null;
        }
        return switch (tradeTypeEnum) {
            case NATIVE -> PayTradeType.NATIVE;
            case JSAPI -> PayTradeType.JSAPI;
            case MWEB -> PayTradeType.H5;
            case APP -> PayTradeType.APP;
            case MICROPAY, FACEPAY -> PayTradeType.BARCODE;
        };
    }

    private PayResponse toPayResponse(com.wechat.pay.java.service.partnerpayments.jsapi.model.Transaction transaction,
                                      WechatPayRequest request,
                                      PayTradeType defaultTradeType) {
        if (transaction == null) {
            return PayResponse.builder()
                    .channel(PayChannel.WECHAT_PAY)
                    .tradeType(defaultTradeType)
                    .outTradeNo(request != null ? request.getOutTradeNo() : null)
                    .tradeState(PayTradeState.NOTPAY)
                    .success(false)
                    .build();
        }

        PayTradeState tradeState = mapTradeState(transaction.getTradeState());
        BigDecimal amount = null;
        if (transaction.getAmount() != null && transaction.getAmount().getTotal() != null) {
            amount = CurrencyUtil.fenToYuan(transaction.getAmount().getTotal().longValue());
        } else if (request != null && request.getAmount() != null) {
            amount = request.getAmount();
        }

        PayTradeType tradeType = mapTradeType(transaction.getTradeType());
        if (tradeType == null) {
            tradeType = defaultTradeType;
        }

        return PayResponse.builder()
                .channel(PayChannel.WECHAT_PAY)
                .tradeType(tradeType)
                .outTradeNo(transaction.getOutTradeNo() != null ? transaction.getOutTradeNo() : (request != null ? request.getOutTradeNo() : null))
                .transactionId(transaction.getTransactionId())
                .tradeState(tradeState)
                .rawTradeState(transaction.getTradeState() != null ? transaction.getTradeState().name() : null)
                .tradeStateDesc(transaction.getTradeStateDesc())
                .amount(amount)
                .success(tradeState.isSuccess())
                .rawResponse(transaction)
                .build();
    }

    // =========================================================================
    // 9. 逃生舱 (Escape Hatches) 与服务持有者获取
    // =========================================================================

    /**
     * 获取默认商户的微信配置对象（逃生舱）
     */
    public Config getWechatConfig() {
        return getServiceHolder(resolveMerchant((String) null)).getConfig();
    }

    /**
     * 获取指定商户的微信配置对象（逃生舱）
     *
     * @param merchantKey 商户别名或商户号
     */
    public Config getWechatConfig(String merchantKey) {
        return getServiceHolder(namedMerchantRegistry.resolveWechatMerchant(merchantKey)).getConfig();
    }

    /**
     * 获取默认商户的官方 HTTP 客户端（逃生舱）
     */
    public HttpClient getHttpClient() {
        return getServiceHolder(resolveMerchant((String) null)).getHttpClient();
    }

    /**
     * 统一客户端逃生舱别名：获取默认商户的官方 HTTP 客户端
     */
    public HttpClient getClient() {
        return getHttpClient();
    }

    /**
     * 获取指定商户的官方 HTTP 客户端（逃生舱）
     *
     * @param merchantKey 商户别名或商户号
     */
    public HttpClient getHttpClient(String merchantKey) {
        return getServiceHolder(namedMerchantRegistry.resolveWechatMerchant(merchantKey)).getHttpClient();
    }

    /**
     * 统一客户端逃生舱别名：获取指定商户的官方 HTTP 客户端
     *
     * @param merchantKey 商户别名或商户号
     */
    public HttpClient getClient(String merchantKey) {
        return getHttpClient(merchantKey);
    }

    /**
     * 获取直连商户 JSAPI 支付服务扩展实例（逃生舱）
     */
    public JsapiServiceExtension getJsapiService() {
        return getServiceHolder(resolveMerchant((String) null)).getJsapiServiceExtension();
    }

    /**
     * 获取直连商户 Native 扫码支付服务实例（逃生舱）
     */
    public NativePayService getNativePayService() {
        return getServiceHolder(resolveMerchant((String) null)).getNativePayService();
    }

    /**
     * 获取直连商户 H5 手机网页支付服务实例（逃生舱）
     */
    public H5Service getH5Service() {
        return getServiceHolder(resolveMerchant((String) null)).getH5Service();
    }

    /**
     * 获取直连商户 App 支付服务扩展实例（逃生舱）
     */
    public AppServiceExtension getAppService() {
        return getServiceHolder(resolveMerchant((String) null)).getAppServiceExtension();
    }

    /**
     * 获取微信退款服务实例（逃生舱）
     */
    public RefundService getRefundService() {
        return getServiceHolder(resolveMerchant((String) null)).getRefundService();
    }

    // =========================================================================
    // 11. 资金分账全生命周期闭环 (Profit Sharing Lifecycle)
    // =========================================================================

    /**
     * 添加分账接收方
     *
     * @param merchantKey 商户配置键或商户号（可选）
     * @param request     添加接收方请求入参
     * @return 添加分账接收方响应实体
     */
    public AddReceiverResponse addProfitSharingReceiver(String merchantKey, AddReceiverRequest request) {
        assertProfitSharingEnabled();
        if (request == null) {
            throw new PayException(PayResultCode.PAY_PARAM_INVALID, "添加分账接收方请求入参不能为空");
        }
        if (request.getType() == null) {
            throw new PayException(PayResultCode.PAY_PARAM_INVALID, "添加分账接收方类型 [type] 不能为空");
        }
        if (request.getAccount() == null || request.getAccount().isBlank()) {
            throw new PayException(PayResultCode.PAY_PARAM_INVALID, "添加分账接收方账号 [account] 不能为空");
        }
        if (request.getRelationType() == null) {
            throw new PayException(PayResultCode.PAY_PARAM_INVALID, "添加分账与分账方的关系类型 [relationType] 不能为空");
        }

        WechatMerchantProperties merchantProps = resolveMerchant(merchantKey);
        WechatServiceHolder holder = getServiceHolder(merchantProps);
        boolean isPartner = isPartnerMode(merchantProps, request.getSubMchid());

        if (request.getAppid() == null || request.getAppid().isBlank()) {
            request.setAppid(merchantProps.getAppId());
        }
        if (isPartner && (request.getSubMchid() == null || request.getSubMchid().isBlank())) {
            request.setSubMchid(resolveSubMchId(merchantProps, request.getSubMchid()));
        }

        return executeWithExceptionTranslation("addProfitSharingReceiver", () ->
                holder.getProfitsharingService().addReceiver(request)
        );
    }

    /**
     * 添加分账接收方（默认商户）
     *
     * @param request 添加接收方请求入参
     * @return 添加分账接收方响应实体
     */
    public AddReceiverResponse addProfitSharingReceiver(AddReceiverRequest request) {
        return addProfitSharingReceiver(null, request);
    }

    /**
     * 删除分账接收方
     *
     * @param merchantKey 商户配置键或商户号（可选）
     * @param request     删除接收方请求入参
     * @return 删除分账接收方响应实体
     */
    public DeleteReceiverResponse deleteProfitSharingReceiver(String merchantKey, DeleteReceiverRequest request) {
        assertProfitSharingEnabled();
        if (request == null) {
            throw new PayException(PayResultCode.PAY_PARAM_INVALID, "删除分账接收方请求入参不能为空");
        }
        if (request.getType() == null) {
            throw new PayException(PayResultCode.PAY_PARAM_INVALID, "删除分账接收方类型 [type] 不能为空");
        }
        if (request.getAccount() == null || request.getAccount().isBlank()) {
            throw new PayException(PayResultCode.PAY_PARAM_INVALID, "删除分账接收方账号 [account] 不能为空");
        }

        WechatMerchantProperties merchantProps = resolveMerchant(merchantKey);
        WechatServiceHolder holder = getServiceHolder(merchantProps);
        boolean isPartner = isPartnerMode(merchantProps, request.getSubMchid());

        if (request.getAppid() == null || request.getAppid().isBlank()) {
            request.setAppid(merchantProps.getAppId());
        }
        if (isPartner && (request.getSubMchid() == null || request.getSubMchid().isBlank())) {
            request.setSubMchid(resolveSubMchId(merchantProps, request.getSubMchid()));
        }

        return executeWithExceptionTranslation("deleteProfitSharingReceiver", () ->
                holder.getProfitsharingService().deleteReceiver(request)
        );
    }

    /**
     * 删除分账接收方（默认商户）
     *
     * @param request 删除接收方请求入参
     * @return 删除分账接收方响应实体
     */
    public DeleteReceiverResponse deleteProfitSharingReceiver(DeleteReceiverRequest request) {
        return deleteProfitSharingReceiver(null, request);
    }

    /**
     * 发起请求分账
     *
     * @param merchantKey 商户配置键或商户号（可选）
     * @param request     请求分账入参
     * @return 微信分账订单实体
     */
    public OrdersEntity createProfitSharing(String merchantKey, CreateOrderRequest request) {
        assertProfitSharingEnabled();
        if (request == null) {
            throw new PayException(PayResultCode.PAY_PARAM_INVALID, "请求分账入参不能为空");
        }
        if (request.getOutOrderNo() == null || request.getOutOrderNo().isBlank()) {
            throw new PayException(PayResultCode.PAY_PARAM_INVALID, "请求分账商户分账单号 [outOrderNo] 不能为空");
        }
        if (request.getTransactionId() == null || request.getTransactionId().isBlank()) {
            throw new PayException(PayResultCode.PAY_PARAM_INVALID, "请求分账微信支付订单号 [transactionId] 不能为空");
        }
        if (request.getReceivers() == null || request.getReceivers().isEmpty()) {
            throw new PayException(PayResultCode.PAY_PARAM_INVALID, "请求分账接收方列表 [receivers] 不能为空");
        }

        WechatMerchantProperties merchantProps = resolveMerchant(merchantKey);
        WechatServiceHolder holder = getServiceHolder(merchantProps);
        boolean isPartner = isPartnerMode(merchantProps, request.getSubMchid());

        if (request.getAppid() == null || request.getAppid().isBlank()) {
            request.setAppid(merchantProps.getAppId());
        }
        if (isPartner && (request.getSubMchid() == null || request.getSubMchid().isBlank())) {
            request.setSubMchid(resolveSubMchId(merchantProps, request.getSubMchid()));
        }
        if (request.getUnfreezeUnsplit() == null) {
            request.setUnfreezeUnsplit(false);
        }

        return executeWithExceptionTranslation("createProfitSharing", () ->
                holder.getProfitsharingService().createOrder(request)
        );
    }

    /**
     * 发起请求分账（默认商户）
     *
     * @param request 请求分账入参
     * @return 微信分账订单实体
     */
    public OrdersEntity createProfitSharing(CreateOrderRequest request) {
        return createProfitSharing(null, request);
    }

    /**
     * 查询分账结果
     *
     * @param merchantKey 商户配置键或商户号（可选）
     * @param request     查询分账结果入参
     * @return 微信分账订单实体
     */
    public OrdersEntity queryProfitSharing(String merchantKey, QueryOrderRequest request) {
        assertProfitSharingEnabled();
        if (request == null) {
            throw new PayException(PayResultCode.PAY_PARAM_INVALID, "查询分账结果入参不能为空");
        }
        if (request.getOutOrderNo() == null || request.getOutOrderNo().isBlank()) {
            throw new PayException(PayResultCode.PAY_PARAM_INVALID, "查询分账商户分账单号 [outOrderNo] 不能为空");
        }
        if (request.getTransactionId() == null || request.getTransactionId().isBlank()) {
            throw new PayException(PayResultCode.PAY_PARAM_INVALID, "查询分账微信支付订单号 [transactionId] 不能为空");
        }

        WechatMerchantProperties merchantProps = resolveMerchant(merchantKey);
        WechatServiceHolder holder = getServiceHolder(merchantProps);
        boolean isPartner = isPartnerMode(merchantProps, request.getSubMchid());

        if (isPartner && (request.getSubMchid() == null || request.getSubMchid().isBlank())) {
            request.setSubMchid(resolveSubMchId(merchantProps, request.getSubMchid()));
        }

        return executeWithExceptionTranslation("queryProfitSharing", () ->
                holder.getProfitsharingService().queryOrder(request)
        );
    }

    /**
     * 查询分账结果（默认商户）
     *
     * @param request 查询分账结果入参
     * @return 微信分账订单实体
     */
    public OrdersEntity queryProfitSharing(QueryOrderRequest request) {
        return queryProfitSharing((String) null, request);
    }

    /**
     * 便捷重载：查询分账结果（默认商户）
     *
     * @param outOrderNo    商户分账单号
     * @param transactionId 微信支付订单号
     * @return 微信分账订单实体
     */
    public OrdersEntity queryProfitSharing(String outOrderNo, String transactionId) {
        QueryOrderRequest req = new QueryOrderRequest();
        req.setOutOrderNo(outOrderNo);
        req.setTransactionId(transactionId);
        return queryProfitSharing((String) null, req);
    }

    /**
     * 便捷重载：查询指定商户的分账结果
     *
     * @param merchantKey   商户别名或商户号
     * @param outOrderNo    商户分账单号
     * @param transactionId 微信支付订单号
     * @return 微信分账订单实体
     */
    public OrdersEntity queryProfitSharing(String merchantKey, String outOrderNo, String transactionId) {
        QueryOrderRequest req = new QueryOrderRequest();
        req.setOutOrderNo(outOrderNo);
        req.setTransactionId(transactionId);
        return queryProfitSharing(merchantKey, req);
    }

    /**
     * 完结分账并解冻订单剩余在途资金 (unfreeze)
     * <p>
     * <b>特别注意：</b>
     * 前置统一下单打了 {@code profit_sharing=true} 标记的订单，在完成分账或无需再分账时，
     * 必须调用此方法主动解冻剩余资金，否则剩余在途资金将被长期冻结沉淀。
     * </p>
     *
     * @param merchantKey 商户配置键或商户号（可选）
     * @param request     解冻请求入参
     * @return 微信分账订单实体
     */
    public OrdersEntity unfreezeProfitSharing(String merchantKey, UnfreezeOrderRequest request) {
        assertProfitSharingEnabled();
        if (request == null) {
            throw new PayException(PayResultCode.PAY_PARAM_INVALID, "完结解冻分账资金请求入参不能为空");
        }
        if (request.getOutOrderNo() == null || request.getOutOrderNo().isBlank()) {
            throw new PayException(PayResultCode.PAY_PARAM_INVALID, "解冻分账商户分账单号 [outOrderNo] 不能为空");
        }
        if (request.getTransactionId() == null || request.getTransactionId().isBlank()) {
            throw new PayException(PayResultCode.PAY_PARAM_INVALID, "解冻分账微信支付订单号 [transactionId] 不能为空");
        }
        if (request.getDescription() == null || request.getDescription().isBlank()) {
            throw new PayException(PayResultCode.PAY_PARAM_INVALID, "解冻分账原因描述 [description] 不能为空");
        }

        WechatMerchantProperties merchantProps = resolveMerchant(merchantKey);
        WechatServiceHolder holder = getServiceHolder(merchantProps);
        boolean isPartner = isPartnerMode(merchantProps, request.getSubMchid());

        if (isPartner && (request.getSubMchid() == null || request.getSubMchid().isBlank())) {
            request.setSubMchid(resolveSubMchId(merchantProps, request.getSubMchid()));
        }

        return executeWithExceptionTranslation("unfreezeProfitSharing", () ->
                holder.getProfitsharingService().unfreezeOrder(request)
        );
    }

    /**
     * 完结分账并解冻订单剩余在途资金（默认商户）
     *
     * @param request 解冻请求入参
     * @return 微信分账订单实体
     */
    public OrdersEntity unfreezeProfitSharing(UnfreezeOrderRequest request) {
        return unfreezeProfitSharing((String) null, request);
    }

    /**
     * 便捷重载：完结解冻分账资金（默认商户）
     *
     * @param outOrderNo    商户分账单号
     * @param transactionId 微信支付订单号
     * @param description   解冻描述
     * @return 微信分账订单实体
     */
    public OrdersEntity unfreezeProfitSharing(String outOrderNo, String transactionId, String description) {
        UnfreezeOrderRequest req = new UnfreezeOrderRequest();
        req.setOutOrderNo(outOrderNo);
        req.setTransactionId(transactionId);
        req.setDescription(description);
        return unfreezeProfitSharing((String) null, req);
    }

    /**
     * 便捷重载：完结解冻指定商户的分账资金
     *
     * @param merchantKey   商户别名或商户号
     * @param outOrderNo    商户分账单号
     * @param transactionId 微信支付订单号
     * @param description   解冻描述
     * @return 微信分账订单实体
     */
    public OrdersEntity unfreezeProfitSharing(String merchantKey, String outOrderNo, String transactionId, String description) {
        UnfreezeOrderRequest req = new UnfreezeOrderRequest();
        req.setOutOrderNo(outOrderNo);
        req.setTransactionId(transactionId);
        req.setDescription(description);
        return unfreezeProfitSharing(merchantKey, req);
    }

    /**
     * 请求分账回退
     *
     * @param merchantKey 商户配置键或商户号（可选）
     * @param request     分账回退请求入参
     * @return 分账回退订单实体
     */
    public ReturnOrdersEntity returnProfitSharing(String merchantKey, CreateReturnOrderRequest request) {
        assertProfitSharingEnabled();
        if (request == null) {
            throw new PayException(PayResultCode.PAY_PARAM_INVALID, "分账回退请求入参不能为空");
        }
        if ((request.getOrderId() == null || request.getOrderId().isBlank())
                && (request.getOutOrderNo() == null || request.getOutOrderNo().isBlank())) {
            throw new PayException(PayResultCode.PAY_PARAM_INVALID, "分账回退必须提供微信分账单号 [orderId] 或商户分账单号 [outOrderNo]");
        }
        if (request.getOutReturnNo() == null || request.getOutReturnNo().isBlank()) {
            throw new PayException(PayResultCode.PAY_PARAM_INVALID, "商户回退单号 [outReturnNo] 不能为空");
        }
        if (request.getReturnMchid() == null || request.getReturnMchid().isBlank()) {
            throw new PayException(PayResultCode.PAY_PARAM_INVALID, "分账回退出资商户号 [returnMchid] 不能为空");
        }
        if (request.getAmount() == null || request.getAmount() <= 0) {
            throw new PayException(PayResultCode.PAY_PARAM_INVALID, "分账回退金额 [amount] 必须大于 0");
        }
        if (request.getDescription() == null || request.getDescription().isBlank()) {
            throw new PayException(PayResultCode.PAY_PARAM_INVALID, "分账回退原因描述 [description] 不能为空");
        }

        WechatMerchantProperties merchantProps = resolveMerchant(merchantKey);
        WechatServiceHolder holder = getServiceHolder(merchantProps);
        boolean isPartner = isPartnerMode(merchantProps, request.getSubMchid());

        if (isPartner && (request.getSubMchid() == null || request.getSubMchid().isBlank())) {
            request.setSubMchid(resolveSubMchId(merchantProps, request.getSubMchid()));
        }

        return executeWithExceptionTranslation("returnProfitSharing", () ->
                holder.getProfitsharingService().createReturnOrder(request)
        );
    }

    /**
     * 请求分账回退（默认商户）
     *
     * @param request 分账回退请求入参
     * @return 分账回退订单实体
     */
    public ReturnOrdersEntity returnProfitSharing(CreateReturnOrderRequest request) {
        return returnProfitSharing(null, request);
    }

    /**
     * 查询分账回退结果
     *
     * @param merchantKey 商户配置键或商户号（可选）
     * @param request     查询分账回退结果请求入参
     * @return 分账回退订单实体
     */
    public ReturnOrdersEntity queryProfitSharingReturn(String merchantKey, QueryReturnOrderRequest request) {
        assertProfitSharingEnabled();
        if (request == null) {
            throw new PayException(PayResultCode.PAY_PARAM_INVALID, "查询分账回退结果入参不能为空");
        }
        if (request.getOutReturnNo() == null || request.getOutReturnNo().isBlank()) {
            throw new PayException(PayResultCode.PAY_PARAM_INVALID, "商户回退单号 [outReturnNo] 不能为空");
        }
        if (request.getOutOrderNo() == null || request.getOutOrderNo().isBlank()) {
            throw new PayException(PayResultCode.PAY_PARAM_INVALID, "商户分账单号 [outOrderNo] 不能为空");
        }

        WechatMerchantProperties merchantProps = resolveMerchant(merchantKey);
        WechatServiceHolder holder = getServiceHolder(merchantProps);
        boolean isPartner = isPartnerMode(merchantProps, request.getSubMchid());

        if (isPartner && (request.getSubMchid() == null || request.getSubMchid().isBlank())) {
            request.setSubMchid(resolveSubMchId(merchantProps, request.getSubMchid()));
        }

        return executeWithExceptionTranslation("queryProfitSharingReturn", () ->
                holder.getProfitsharingService().queryReturnOrder(request)
        );
    }

    /**
     * 查询分账回退结果（默认商户）
     *
     * @param request 查询分账回退结果请求入参
     * @return 分账回退订单实体
     */
    public ReturnOrdersEntity queryProfitSharingReturn(QueryReturnOrderRequest request) {
        return queryProfitSharingReturn((String) null, request);
    }

    /**
     * 便捷重载：查询分账回退结果（默认商户）
     *
     * @param outReturnNo 商户回退单号
     * @param outOrderNo  商户分账单号
     * @return 分账回退订单实体
     */
    public ReturnOrdersEntity queryProfitSharingReturn(String outReturnNo, String outOrderNo) {
        QueryReturnOrderRequest req = new QueryReturnOrderRequest();
        req.setOutReturnNo(outReturnNo);
        req.setOutOrderNo(outOrderNo);
        return queryProfitSharingReturn((String) null, req);
    }

    /**
     * 便捷重载：查询指定商户的分账回退结果
     *
     * @param merchantKey 商户别名或商户号
     * @param outReturnNo 商户回退单号
     * @param outOrderNo  商户分账单号
     * @return 分账回退订单实体
     */
    public ReturnOrdersEntity queryProfitSharingReturn(String merchantKey, String outReturnNo, String outOrderNo) {
        QueryReturnOrderRequest req = new QueryReturnOrderRequest();
        req.setOutReturnNo(outReturnNo);
        req.setOutOrderNo(outOrderNo);
        return queryProfitSharingReturn(merchantKey, req);
    }

    /**
     * 查询剩余待分金额
     *
     * @param merchantKey 商户配置键或商户号（可选）
     * @param request     查询待分金额请求
     * @return 待分金额响应实体
     */
    public QueryOrderAmountResponse queryProfitSharingAmount(String merchantKey, QueryOrderAmountRequest request) {
        assertProfitSharingEnabled();
        if (request == null) {
            throw new PayException(PayResultCode.PAY_PARAM_INVALID, "查询待分金额请求入参不能为空");
        }
        if (request.getTransactionId() == null || request.getTransactionId().isBlank()) {
            throw new PayException(PayResultCode.PAY_PARAM_INVALID, "微信支付订单号 [transactionId] 不能为空");
        }
        WechatMerchantProperties merchantProps = resolveMerchant(merchantKey);
        WechatServiceHolder holder = getServiceHolder(merchantProps);
        return executeWithExceptionTranslation("queryProfitSharingAmount", () ->
                holder.getProfitsharingService().queryOrderAmount(request)
        );
    }

    /**
     * 查询剩余待分金额（默认商户）
     *
     * @param request 查询待分金额请求
     * @return 待分金额响应实体
     */
    public QueryOrderAmountResponse queryProfitSharingAmount(QueryOrderAmountRequest request) {
        return queryProfitSharingAmount(null, request);
    }

    /**
     * 查询最大分账比例
     *
     * @param merchantKey 商户配置键或商户号（可选）
     * @param request     查询最大分账比例请求
     * @return 最大分账比例响应实体
     */
    public QueryMerchantRatioResponse queryProfitSharingMerchantRatio(String merchantKey, QueryMerchantRatioRequest request) {
        assertProfitSharingEnabled();
        if (request == null) {
            throw new PayException(PayResultCode.PAY_PARAM_INVALID, "查询最大分账比例请求入参不能为空");
        }
        if (request.getSubMchid() == null || request.getSubMchid().isBlank()) {
            throw new PayException(PayResultCode.PAY_PARAM_INVALID, "子商户号 [subMchid] 不能为空");
        }
        WechatMerchantProperties merchantProps = resolveMerchant(merchantKey);
        WechatServiceHolder holder = getServiceHolder(merchantProps);
        return executeWithExceptionTranslation("queryProfitSharingMerchantRatio", () ->
                holder.getProfitsharingService().queryMerchantRatio(request)
        );
    }

    /**
     * 查询最大分账比例（默认商户）
     *
     * @param request 查询最大分账比例请求
     * @return 最大分账比例响应实体
     */
    public QueryMerchantRatioResponse queryProfitSharingMerchantRatio(QueryMerchantRatioRequest request) {
        return queryProfitSharingMerchantRatio(null, request);
    }

    /**
     * 获取指定商户的官方微信分账服务实例（逃生舱）
     *
     * @param merchantKey 商户配置键或商户号
     * @return 官方 ProfitsharingService 原生实例
     */
    public ProfitsharingService getProfitsharingService(String merchantKey) {
        assertProfitSharingEnabled();
        WechatMerchantProperties merchantProps = resolveMerchant(merchantKey);
        return getServiceHolder(merchantProps).getProfitsharingService();
    }

    /**
     * 获取默认商户的官方微信分账服务实例（逃生舱）
     *
     * @return 官方 ProfitsharingService 原生实例
     */
    public ProfitsharingService getProfitsharingService() {
        return getProfitsharingService(null);
    }

    // =========================================================================
    // 10. 商家转账到零钱 (transfer)
    // =========================================================================

    /**
     * 发起微信商家转账到零钱
     *
     * @param merchantKey 商户别名或商户号（可选）
     * @param request     微信转账专有请求参数
     * @return 统一转账响应出参
     */
    public PayTransferResponse transfer(String merchantKey, WechatTransferRequest request) {
        assertTransferEnabled();
        validateTransferRequest(request);

        String key = (merchantKey != null && !merchantKey.isBlank()) ? merchantKey : request.getMerchantKey();
        WechatMerchantProperties merchantProps = resolveMerchant(key);
        WechatServiceHolder holder = getServiceHolder(merchantProps);

        String outBatchNo = request.getOutBatchNo();
        String outDetailNo = (request.getOutDetailNo() != null && !request.getOutDetailNo().isBlank())
                ? request.getOutDetailNo()
                : outBatchNo;
        String batchName = (request.getBatchName() != null && !request.getBatchName().isBlank())
                ? request.getBatchName()
                : "单笔转账";
        String batchRemark = (request.getBatchRemark() != null && !request.getBatchRemark().isBlank())
                ? request.getBatchRemark()
                : batchName;
        String transferRemark = (request.getTransferRemark() != null && !request.getTransferRemark().isBlank())
                ? request.getTransferRemark()
                : batchName;

        Long amountInFen = CurrencyUtil.yuanToFen(request.getAmount());

        InitiateBatchTransferRequest sdkReq = new InitiateBatchTransferRequest();
        sdkReq.setAppid(merchantProps.getAppId());
        sdkReq.setOutBatchNo(outBatchNo);
        sdkReq.setBatchName(batchName);
        sdkReq.setBatchRemark(batchRemark);
        sdkReq.setTotalAmount(amountInFen);
        sdkReq.setTotalNum(1);
        if (request.getTransferSceneId() != null && !request.getTransferSceneId().isBlank()) {
            sdkReq.setTransferSceneId(request.getTransferSceneId());
        }
        String notifyUrl = request.getNotifyUrl() != null ? request.getNotifyUrl() : merchantProps.getNotifyUrl();
        if (notifyUrl != null && !notifyUrl.isBlank()) {
            sdkReq.setNotifyUrl(notifyUrl);
        }

        TransferDetailInput detail = new TransferDetailInput();
        detail.setOutDetailNo(outDetailNo);
        detail.setTransferAmount(amountInFen);
        detail.setTransferRemark(transferRemark);
        detail.setOpenid(request.getOpenid());
        if (request.getUserName() != null && !request.getUserName().isBlank()) {
            detail.setUserName(request.getUserName());
        }
        sdkReq.setTransferDetailList(java.util.List.of(detail));

        log.debug("Initiating WeChat single transfer: outBatchNo={}, outDetailNo={}, amount={}, openid={}, userName={}",
                outBatchNo, outDetailNo, request.getAmount(),
                DesensitizeUtil.maskKeepHeadTail(request.getOpenid(), 3, 3),
                DesensitizeUtil.chineseName(request.getUserName()));

        InitiateBatchTransferResponse sdkResponse = executeWithExceptionTranslation("initiateBatchTransfer", () ->
                holder.getTransferBatchService().initiateBatchTransfer(sdkReq)
        );

        PayTransferState state = mapWechatBatchStatus(sdkResponse.getBatchStatus());

        return PayTransferResponse.builder()
                .channel(PayChannel.WECHAT_PAY)
                .outBizNo(sdkResponse.getOutBatchNo())
                .orderId(sdkResponse.getBatchId())
                .status(state)
                .amount(request.getAmount())
                .payeeAccount(request.getOpenid())
                .payeeRealName(request.getUserName())
                .payTime(sdkResponse.getCreateTime())
                .rawResponse(sdkResponse)
                .build();
    }

    /**
     * 发起微信商家转账到零钱（默认商户）
     *
     * @param request 微信转账专有请求参数
     * @return 统一转账响应出参
     */
    public PayTransferResponse transfer(WechatTransferRequest request) {
        return transfer(null, request);
    }

    /**
     * 查询微信商家转账明细单状态
     *
     * @param merchantKey 商户别名或商户号（可选）
     * @param outBatchNo  商家批次单号
     * @param outDetailNo 商家明细单号
     * @return 统一转账响应出参
     */
    public PayTransferResponse queryTransferDetail(String merchantKey, String outBatchNo, String outDetailNo) {
        assertTransferEnabled();
        if (outBatchNo == null || outBatchNo.isBlank()) {
            throw new PayException(PayResultCode.PAY_PARAM_INVALID, "商家批次单号 [outBatchNo] 不能为空");
        }
        String realDetailNo = (outDetailNo != null && !outDetailNo.isBlank()) ? outDetailNo : outBatchNo;

        WechatMerchantProperties merchantProps = resolveMerchant(merchantKey);
        WechatServiceHolder holder = getServiceHolder(merchantProps);

        GetTransferDetailByOutNoRequest queryReq = new GetTransferDetailByOutNoRequest();
        queryReq.setOutBatchNo(outBatchNo);
        queryReq.setOutDetailNo(realDetailNo);

        TransferDetailEntity detail = executeWithExceptionTranslation("getTransferDetailByOutNo", () ->
                holder.getTransferBatchService().getTransferDetailByOutNo(queryReq)
        );

        PayTransferState state = mapWechatDetailStatus(detail.getDetailStatus());
        String failReason = detail.getFailReason() != null ? detail.getFailReason().name() : null;

        return PayTransferResponse.builder()
                .channel(PayChannel.WECHAT_PAY)
                .outBizNo(detail.getOutDetailNo())
                .orderId(detail.getBatchId())
                .payFundOrderId(detail.getDetailId())
                .status(state)
                .amount(CurrencyUtil.fenToYuan(detail.getTransferAmount()))
                .payeeAccount(detail.getOpenid())
                .payeeRealName(detail.getUserName())
                .payTime(detail.getUpdateTime() != null ? detail.getUpdateTime() : detail.getInitiateTime())
                .failReason(failReason)
                .rawResponse(detail)
                .build();
    }

    /**
     * 查询微信商家转账明细单状态（默认商户）
     *
     * @param outBatchNo  商家批次单号
     * @param outDetailNo 商家明细单号
     * @return 统一转账响应出参
     */
    public PayTransferResponse queryTransferDetail(String outBatchNo, String outDetailNo) {
        return queryTransferDetail(null, outBatchNo, outDetailNo);
    }

    /**
     * 查询微信商家转账批次单详情
     *
     * @param merchantKey 商户别名或商户号（可选）
     * @param outBatchNo  商家批次单号
     * @return 官方 TransferBatchEntity 原生实体
     */
    public TransferBatchEntity queryTransferBatch(String merchantKey, String outBatchNo) {
        assertTransferEnabled();
        if (outBatchNo == null || outBatchNo.isBlank()) {
            throw new PayException(PayResultCode.PAY_PARAM_INVALID, "商家批次单号 [outBatchNo] 不能为空");
        }
        WechatMerchantProperties merchantProps = resolveMerchant(merchantKey);
        WechatServiceHolder holder = getServiceHolder(merchantProps);

        GetTransferBatchByOutNoRequest request = new GetTransferBatchByOutNoRequest();
        request.setOutBatchNo(outBatchNo);
        request.setNeedQueryDetail(true);

        return executeWithExceptionTranslation("getTransferBatchByOutNo", () ->
                holder.getTransferBatchService().getTransferBatchByOutNo(request)
        );
    }

    /**
     * 查询微信商家转账批次单详情（默认商户）
     *
     * @param outBatchNo 商家批次单号
     * @return 官方 TransferBatchEntity 原生实体
     */
    public TransferBatchEntity queryTransferBatch(String outBatchNo) {
        return queryTransferBatch(null, outBatchNo);
    }

    /**
     * 获取指定商户的官方微信商家转账服务实例（逃生舱）
     *
     * @param merchantKey 商户别名或商户号
     * @return 官方 TransferBatchService 原生实例
     */
    public TransferBatchService getTransferBatchService(String merchantKey) {
        assertTransferEnabled();
        WechatMerchantProperties merchantProps = resolveMerchant(merchantKey);
        return getServiceHolder(merchantProps).getTransferBatchService();
    }

    /**
     * 获取默认商户的官方微信商家转账服务实例（逃生舱）
     *
     * @return 官方 TransferBatchService 原生实例
     */
    public TransferBatchService getTransferBatchService() {
        return getTransferBatchService(null);
    }

    /**
     * 将微信批次单状态转换为标准化转账状态机
     *
     * @param batchStatus 微信 batch_status
     * @return 标准化转账状态
     */
    public static PayTransferState mapWechatBatchStatus(String batchStatus) {
        if (batchStatus == null || batchStatus.isBlank()) {
            return PayTransferState.PROCESSING;
        }
        return switch (batchStatus.trim()) {
            case "FINISHED", "SUCCESS" -> PayTransferState.SUCCESS;
            case "CLOSED" -> PayTransferState.CLOSED;
            case "WAIT_PAY", "ACCEPTED", "PROCESSING" -> PayTransferState.PROCESSING;
            default -> PayTransferState.PROCESSING;
        };
    }

    /**
     * 将微信明细单状态转换为标准化转账状态机
     *
     * @param detailStatus 微信 detail_status
     * @return 标准化转账状态
     */
    public static PayTransferState mapWechatDetailStatus(String detailStatus) {
        if (detailStatus == null || detailStatus.isBlank()) {
            return PayTransferState.PROCESSING;
        }
        return switch (detailStatus.trim()) {
            case "SUCCESS" -> PayTransferState.SUCCESS;
            case "FAIL" -> PayTransferState.FAIL;
            case "INIT", "WAIT_PAY", "PROCESSING" -> PayTransferState.PROCESSING;
            default -> PayTransferState.PROCESSING;
        };
    }

    private void validateTransferRequest(WechatTransferRequest request) {
        if (request == null) {
            throw new PayException(PayResultCode.PAY_PARAM_INVALID, "微信转账请求参数不能为空");
        }
        if (request.getOutBatchNo() == null || request.getOutBatchNo().isBlank()) {
            throw new PayException(PayResultCode.PAY_PARAM_INVALID, "商家批次单号 [outBatchNo] 不能为空");
        }
        if (request.getAmount() == null || request.getAmount().compareTo(BigDecimal.ZERO) <= 0) {
            throw new PayException(PayResultCode.PAY_AMOUNT_INVALID, "转账金额 [amount] 必须大于 0 元");
        }
        BigDecimal stripped = request.getAmount().stripTrailingZeros();
        if (stripped.scale() > 2) {
            throw new PayException(PayResultCode.PAY_AMOUNT_INVALID,
                    "转账金额小数位不能超过 2 位（分）：" + request.getAmount());
        }
        if (request.getOpenid() == null || request.getOpenid().isBlank()) {
            throw new PayException(PayResultCode.PAY_PARAM_INVALID, "收款人 openid 不能为空");
        }
    }

    // =========================================================================
    // 10. 财务对账单下载与校验 (Bill Download & Verification)
    // =========================================================================

    /**
     * 申请并下载交易对账单（返回包含流与 Hash 校验的实体）
     *
     * @param request 交易账单请求参数
     * @return 包含输入流与 Hash 校验的实体
     */
    public DigestBillEntity downloadTradeBill(GetTradeBillRequest request) {
        return downloadTradeBill(null, request);
    }

    /**
     * 申请并下载交易对账单（指定多商户标识）
     *
     * @param merchantKey 多商户标识
     * @param request     交易账单请求参数
     * @return 包含输入流与 Hash 校验的实体
     */
    public DigestBillEntity downloadTradeBill(String merchantKey, GetTradeBillRequest request) {
        if (request == null || request.getBillDate() == null || request.getBillDate().isBlank()) {
            throw new PayException(PayResultCode.PAY_PARAM_INVALID, "对账单日期 (billDate) 不能为空");
        }
        WechatMerchantProperties merchantProps = resolveMerchant(merchantKey);
        WechatServiceHolder holder = getServiceHolder(merchantProps);
        return executeWithExceptionTranslation("downloadTradeBill", () ->
                holder.billDownloadServiceExtension.getTradeBill(request)
        );
    }

    /**
     * 申请并下载资金账单（返回包含流与 Hash 校验的实体）
     *
     * @param request 资金账单请求参数
     * @return 包含输入流与 Hash 校验的实体
     */
    public DigestBillEntity downloadFundFlowBill(GetFundFlowBillRequest request) {
        return downloadFundFlowBill(null, request);
    }

    /**
     * 申请并下载资金账单（指定多商户标识）
     *
     * @param merchantKey 多商户标识
     * @param request     资金账单请求参数
     * @return 包含输入流与 Hash 校验的实体
     */
    public DigestBillEntity downloadFundFlowBill(String merchantKey, GetFundFlowBillRequest request) {
        if (request == null || request.getBillDate() == null || request.getBillDate().isBlank()) {
            throw new PayException(PayResultCode.PAY_PARAM_INVALID, "对账单日期 (billDate) 不能为空");
        }
        WechatMerchantProperties merchantProps = resolveMerchant(merchantKey);
        WechatServiceHolder holder = getServiceHolder(merchantProps);
        return executeWithExceptionTranslation("downloadFundFlowBill", () ->
                holder.billDownloadServiceExtension.getFundFlowBill(request)
        );
    }

    /**
     * 查询交易对账单下载地址与 Hash 校验元数据（不直接拉取文件流）
     *
     * @param request 交易账单请求参数
     * @return 对账单下载元数据（含 downloadUrl 与 hashValue）
     */
    public QueryBillEntity queryTradeBill(GetTradeBillRequest request) {
        return queryTradeBill(null, request);
    }

    /**
     * 查询交易对账单下载地址与 Hash 校验元数据（指定商户标识）
     *
     * @param merchantKey 多商户标识
     * @param request     交易账单请求参数
     * @return 对账单下载元数据
     */
    public QueryBillEntity queryTradeBill(String merchantKey, GetTradeBillRequest request) {
        if (request == null || request.getBillDate() == null || request.getBillDate().isBlank()) {
            throw new PayException(PayResultCode.PAY_PARAM_INVALID, "对账单日期 (billDate) 不能为空");
        }
        WechatMerchantProperties merchantProps = resolveMerchant(merchantKey);
        WechatServiceHolder holder = getServiceHolder(merchantProps);
        return executeWithExceptionTranslation("queryTradeBill", () ->
                holder.billDownloadService.getTradeBill(request)
        );
    }

    /**
     * 查询资金账单下载地址与 Hash 校验元数据
     *
     * @param request 资金账单请求参数
     * @return 对账单下载元数据
     */
    public QueryBillEntity queryFundFlowBill(GetFundFlowBillRequest request) {
        return queryFundFlowBill(null, request);
    }

    /**
     * 查询资金账单下载地址与 Hash 校验元数据（指定商户标识）
     *
     * @param merchantKey 多商户标识
     * @param request     资金账单请求参数
     * @return 对账单下载元数据
     */
    public QueryBillEntity queryFundFlowBill(String merchantKey, GetFundFlowBillRequest request) {
        if (request == null || request.getBillDate() == null || request.getBillDate().isBlank()) {
            throw new PayException(PayResultCode.PAY_PARAM_INVALID, "对账单日期 (billDate) 不能为空");
        }
        WechatMerchantProperties merchantProps = resolveMerchant(merchantKey);
        WechatServiceHolder holder = getServiceHolder(merchantProps);
        return executeWithExceptionTranslation("queryFundFlowBill", () ->
                holder.billDownloadService.getFundFlowBill(request)
        );
    }

    /**
     * 根据对账单直链下载地址直接拉取原始输入流
     *
     * @param downloadUrl 账单下载地址
     * @return 原始对账单输入流
     */
    public InputStream downloadBillStream(String downloadUrl) {
        return downloadBillStream(null, downloadUrl);
    }

    /**
     * 根据对账单直链下载地址直接拉取原始输入流（指定商户标识）
     *
     * @param merchantKey 多商户标识
     * @param downloadUrl 账单下载地址
     * @return 原始对账单输入流
     */
    public InputStream downloadBillStream(String merchantKey, String downloadUrl) {
        if (downloadUrl == null || downloadUrl.isBlank()) {
            throw new PayException(PayResultCode.PAY_PARAM_INVALID, "对账单下载地址不能为空");
        }
        WechatMerchantProperties merchantProps = resolveMerchant(merchantKey);
        WechatServiceHolder holder = getServiceHolder(merchantProps);
        return executeWithExceptionTranslation("downloadBillStream", () ->
                holder.httpClient.download(downloadUrl)
        );
    }

    /**
     * 获取微信官方对账单原生服务（直连模式逃生舱）
     *
     * @param merchantKey 多商户标识
     * @return 微信官方 BillDownloadService
     */
    public BillDownloadService getBillDownloadService(String merchantKey) {
        return getServiceHolder(resolveMerchant(merchantKey)).getBillDownloadService();
    }

    /**
     * 获取微信官方对账单带校验扩展服务（逃生舱）
     *
     * @param merchantKey 多商户标识
     * @return 微信官方 BillDownloadServiceExtension
     */
    public BillDownloadServiceExtension getBillDownloadServiceExtension(String merchantKey) {
        return getServiceHolder(resolveMerchant(merchantKey)).getBillDownloadServiceExtension();
    }

    // =========================================================================
    // 11. 私有解析与因果异常转译机制
    // =========================================================================

    private WechatMerchantProperties resolveMerchant(WechatPayRequest request) {
        String key = request != null ? (request.getMerchantKey() != null ? request.getMerchantKey() : request.getMchId()) : null;
        return resolveMerchant(key);
    }

    private WechatMerchantProperties resolveMerchant(String merchantKey) {
        String key = merchantKey;
        if (key == null || key.isBlank()) {
            key = io.github.atengk.boot.pay.core.context.PayContextHolder.getMerchantId();
        }
        return namedMerchantRegistry.resolveWechatMerchant(key);
    }

    private boolean isPartnerMode(WechatMerchantProperties merchantProps, WechatPayRequest request) {
        if (request != null) {
            if (request.getSpMchId() != null && !request.getSpMchId().isBlank()) {
                return true;
            }
            if (request.getSubMchId() != null && !request.getSubMchId().isBlank()) {
                return true;
            }
        }
        return merchantProps.getSpMchId() != null && !merchantProps.getSpMchId().isBlank();
    }

    private boolean isPartnerMode(WechatMerchantProperties merchantProps, String subMchId) {
        if (subMchId != null && !subMchId.isBlank()) {
            return true;
        }
        return merchantProps.getSpMchId() != null && !merchantProps.getSpMchId().isBlank();
    }

    private String resolveAppId(WechatMerchantProperties merchantProps, WechatPayRequest request) {
        if (request != null && request.getAppId() != null && !request.getAppId().isBlank()) {
            return request.getAppId();
        }
        return merchantProps.getAppId();
    }

    private String resolveSpAppId(WechatMerchantProperties merchantProps, WechatPayRequest request) {
        if (request != null && request.getSpAppId() != null && !request.getSpAppId().isBlank()) {
            return request.getSpAppId();
        }
        if (merchantProps.getSpAppId() != null && !merchantProps.getSpAppId().isBlank()) {
            return merchantProps.getSpAppId();
        }
        return resolveAppId(merchantProps, request);
    }

    private String resolveSpMchId(WechatMerchantProperties merchantProps, WechatPayRequest request) {
        if (request != null && request.getSpMchId() != null && !request.getSpMchId().isBlank()) {
            return request.getSpMchId();
        }
        if (merchantProps.getSpMchId() != null && !merchantProps.getSpMchId().isBlank()) {
            return merchantProps.getSpMchId();
        }
        return merchantProps.getMchId();
    }

    private String resolveSubMchId(WechatMerchantProperties merchantProps, WechatPayRequest request) {
        if (request != null && request.getSubMchId() != null && !request.getSubMchId().isBlank()) {
            return request.getSubMchId();
        }
        return merchantProps.getSubMchId();
    }

    private String resolveSubMchId(WechatMerchantProperties merchantProps, String subMchId) {
        if (subMchId != null && !subMchId.isBlank()) {
            return subMchId;
        }
        return merchantProps.getSubMchId();
    }

    private String resolveSubAppId(WechatMerchantProperties merchantProps, WechatPayRequest request) {
        if (request != null && request.getSubAppId() != null && !request.getSubAppId().isBlank()) {
            return request.getSubAppId();
        }
        return merchantProps.getSubAppId();
    }

    private String resolveNotifyUrl(WechatMerchantProperties merchantProps, WechatPayRequest request) {
        String notifyUrl = request != null && request.getNotifyUrl() != null && !request.getNotifyUrl().isBlank()
                ? request.getNotifyUrl()
                : merchantProps.getNotifyUrl();
        if (notifyUrl == null || notifyUrl.isBlank()) {
            throw new PayException(PayResultCode.PAY_PARAM_INVALID, "微信支付回调地址 [notifyUrl] 不能为空，请在请求或配置中提供");
        }
        return notifyUrl;
    }

    private void validateCheckoutRequest(WechatPayRequest request) {
        if (request == null) {
            throw new PayException(PayResultCode.PAY_PARAM_INVALID, "微信支付请求入参不能为空");
        }
        if (request.getOutTradeNo() == null || request.getOutTradeNo().isBlank()) {
            throw new PayException(PayResultCode.PAY_PARAM_INVALID, "微信支付商户订单号 [outTradeNo] 不能为空");
        }
        if (request.getDescription() == null || request.getDescription().isBlank()) {
            throw new PayException(PayResultCode.PAY_PARAM_INVALID, "微信支付商品描述 [description] 不能为空");
        }
        if (request.getAmount() == null || request.getAmount().compareTo(BigDecimal.ZERO) <= 0) {
            throw new PayException(PayResultCode.PAY_AMOUNT_INVALID, "微信支付订单金额必须大于 0 元");
        }
    }

    private String extractPrepayId(String packageVal) {
        if (packageVal != null && packageVal.startsWith("prepay_id=")) {
            return packageVal.substring("prepay_id=".length());
        }
        return packageVal;
    }

    private WechatServiceHolder getServiceHolder(WechatMerchantProperties props) {
        String key = props.getMchId();
        if (key == null || key.isBlank()) {
            throw new PayException(PayResultCode.PAY_PARAM_INVALID, "微信商户号 [mchId] 不能为空");
        }
        return holderCache.computeIfAbsent(key, k -> createServiceHolder(props));
    }

    private WechatServiceHolder createServiceHolder(WechatMerchantProperties props) {
        String merchantId = props.getMchId();
        String serialNumber = props.getMerchantSerialNumber();
        if (serialNumber == null || serialNumber.isBlank()) {
            throw new PayException(PayResultCode.PAY_PARAM_INVALID, "微信商户证书序列号 [merchantSerialNumber] 不能为空");
        }

        String keySource = props.getPrivateKeyPem() != null && !props.getPrivateKeyPem().isBlank()
                ? props.getPrivateKeyPem()
                : props.getPrivateKeyPath();
        if (keySource == null || keySource.isBlank()) {
            throw new PayException(PayResultCode.PAY_PARAM_INVALID, "微信商户私钥 [privateKeyPem / privateKeyPath] 不能为空");
        }
        PrivateKey privateKey = PayKeyLoader.loadPrivateKey(keySource);

        Config config;
        String pubKeySource = props.getWechatPayPublicKey() != null && !props.getWechatPayPublicKey().isBlank()
                ? props.getWechatPayPublicKey()
                : props.getWechatPayPublicKeyPath();
        if (pubKeySource != null && !pubKeySource.isBlank()) {
            PublicKey pubKey = PayKeyLoader.loadPublicKey(pubKeySource);
            String pubKeyId = props.getWechatPayPublicKeyId() != null && !props.getWechatPayPublicKeyId().isBlank()
                    ? props.getWechatPayPublicKeyId()
                    : serialNumber;
            RSAPublicKeyConfig.Builder builder = new RSAPublicKeyConfig.Builder()
                    .merchantId(merchantId)
                    .privateKey(privateKey)
                    .merchantSerialNumber(serialNumber)
                    .publicKey(pubKey)
                    .publicKeyId(pubKeyId);
            if (props.getApiV3Key() != null && !props.getApiV3Key().isBlank()) {
                builder.apiV3Key(props.getApiV3Key());
            }
            config = builder.build();
        } else {
            CertificateProvider certProvider = new CertificateProvider() {
                @Override
                public X509Certificate getCertificate(String serialNumber) {
                    return wechatAutoCertificateManager.getCertificate(merchantId, serialNumber);
                }

                @Override
                public X509Certificate getAvailableCertificate() {
                    return wechatAutoCertificateManager.getAvailableCertificate(merchantId);
                }
            };
            config = new AbstractRSAConfig(merchantId, privateKey, serialNumber, certProvider) {};
        }

        HttpClient httpClient = customHttpClient != null
                ? customHttpClient
                : new DefaultHttpClientBuilder().config(config).build();

        return new WechatServiceHolder(config, httpClient);
    }

    private <T> T executeWithExceptionTranslation(String operation, Supplier<T> supplier) {
        try {
            return supplier.get();
        } catch (ValidationException e) {
            throw new PaySignException(PayResultCode.PAY_SIGN_VERIFY_ERROR, "微信支付应答签名校验失败: " + e.getMessage(), e);
        } catch (ServiceException e) {
            PayResultCode resultCode = PayResultCode.PAY_GATEWAY_ERROR;
            if ("ORDERNOTEXIST".equalsIgnoreCase(e.getErrorCode()) || "ORDER_NOT_EXIST".equalsIgnoreCase(e.getErrorCode())) {
                resultCode = PayResultCode.PAY_ORDER_NOT_FOUND;
            }
            throw new PayTradeException(resultCode, e.getErrorCode(), e.getErrorMessage(), e.getHttpStatusCode(), e);
        } catch (HttpException e) {
            throw new PayNetworkException("微信支付网络通信异常: " + e.getMessage(), e);
        } catch (MalformedMessageException e) {
            throw new PayException(PayResultCode.PAY_GATEWAY_ERROR, "微信支付响应报文格式异常: " + e.getMessage(), e);
        } catch (PayException e) {
            throw e;
        } catch (Exception e) {
            throw new PayException(PayResultCode.PAY_GATEWAY_ERROR, "微信支付操作执行失败: " + e.getMessage(), e);
        }
    }

    /**
     * 单商户微信官方 SDK 客户端服务持有容器
     */
    private static class WechatServiceHolder {
        private final Config config;
        private final HttpClient httpClient;

        // 直连服务
        private final NativePayService nativePayService;
        private final JsapiServiceExtension jsapiServiceExtension;
        private final H5Service h5Service;
        private final AppServiceExtension appServiceExtension;
        private final RefundService refundService;
        private final ProfitsharingService profitsharingService;
        private final TransferBatchService transferBatchService;
        private final BillDownloadService billDownloadService;
        private final BillDownloadServiceExtension billDownloadServiceExtension;

        // 服务商服务
        private final com.wechat.pay.java.service.partnerpayments.nativepay.NativePayService partnerNativePayService;
        private final com.wechat.pay.java.service.partnerpayments.jsapi.JsapiServiceExtension partnerJsapiServiceExtension;
        private final com.wechat.pay.java.service.partnerpayments.h5.H5Service partnerH5Service;
        private final com.wechat.pay.java.service.partnerpayments.app.AppServiceExtension partnerAppServiceExtension;

        public WechatServiceHolder(Config config, HttpClient httpClient) {
            this.config = config;
            this.httpClient = httpClient;

            this.nativePayService = new NativePayService.Builder().config(config).httpClient(httpClient).build();
            this.jsapiServiceExtension = new JsapiServiceExtension.Builder().config(config).httpClient(httpClient).build();
            this.h5Service = new H5Service.Builder().config(config).httpClient(httpClient).build();
            this.appServiceExtension = new AppServiceExtension.Builder().config(config).httpClient(httpClient).build();
            this.refundService = new RefundService.Builder().config(config).httpClient(httpClient).build();
            this.profitsharingService = new ProfitsharingService.Builder().config(config).httpClient(httpClient).build();
            this.transferBatchService = new TransferBatchService.Builder().config(config).httpClient(httpClient).build();
            this.billDownloadService = new BillDownloadService.Builder().config(config).httpClient(httpClient).build();
            this.billDownloadServiceExtension = new BillDownloadServiceExtension.Builder().config(config).httpClient(httpClient).build();

            this.partnerNativePayService = new com.wechat.pay.java.service.partnerpayments.nativepay.NativePayService.Builder()
                    .config(config).httpClient(httpClient).build();
            this.partnerJsapiServiceExtension = new com.wechat.pay.java.service.partnerpayments.jsapi.JsapiServiceExtension.Builder()
                    .config(config).httpClient(httpClient).build();
            this.partnerH5Service = new com.wechat.pay.java.service.partnerpayments.h5.H5Service.Builder()
                    .config(config).httpClient(httpClient).build();
            this.partnerAppServiceExtension = new com.wechat.pay.java.service.partnerpayments.app.AppServiceExtension.Builder()
                    .config(config).httpClient(httpClient).build();
        }

        public Config getConfig() {
            return config;
        }

        public HttpClient getHttpClient() {
            return httpClient;
        }

        public NativePayService getNativePayService() {
            return nativePayService;
        }

        public JsapiServiceExtension getJsapiServiceExtension() {
            return jsapiServiceExtension;
        }

        public H5Service getH5Service() {
            return h5Service;
        }

        public AppServiceExtension getAppServiceExtension() {
            return appServiceExtension;
        }

        public RefundService getRefundService() {
            return refundService;
        }

        public ProfitsharingService getProfitsharingService() {
            return profitsharingService;
        }

        public TransferBatchService getTransferBatchService() {
            return transferBatchService;
        }

        public BillDownloadService getBillDownloadService() {
            return billDownloadService;
        }

        public BillDownloadServiceExtension getBillDownloadServiceExtension() {
            return billDownloadServiceExtension;
        }

        public com.wechat.pay.java.service.partnerpayments.nativepay.NativePayService getPartnerNativePayService() {
            return partnerNativePayService;
        }

        public com.wechat.pay.java.service.partnerpayments.jsapi.JsapiServiceExtension getPartnerJsapiServiceExtension() {
            return partnerJsapiServiceExtension;
        }

        public com.wechat.pay.java.service.partnerpayments.h5.H5Service getPartnerH5Service() {
            return partnerH5Service;
        }

        public com.wechat.pay.java.service.partnerpayments.app.AppServiceExtension getPartnerAppServiceExtension() {
            return partnerAppServiceExtension;
        }
    }
}
