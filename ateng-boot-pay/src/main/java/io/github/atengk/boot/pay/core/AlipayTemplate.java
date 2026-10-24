package io.github.atengk.boot.pay.core;

import com.alipay.api.*;
import com.alipay.api.domain.*;
import com.alipay.api.request.*;
import com.alipay.api.response.*;
import io.github.atengk.boot.core.util.DesensitizeUtil;
import io.github.atengk.boot.pay.config.properties.AlipayAppProperties;
import io.github.atengk.boot.pay.config.properties.PayProperties;
import io.github.atengk.boot.pay.core.context.PayContextHolder;
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
import io.github.atengk.boot.pay.model.request.AlipayPayRequest;
import io.github.atengk.boot.pay.model.request.AlipayRefundRequest;
import io.github.atengk.boot.pay.model.request.AlipayTransferRequest;
import io.github.atengk.boot.pay.model.request.UnifiedRefundRequest;
import io.github.atengk.boot.pay.model.response.PayRefundResponse;
import io.github.atengk.boot.pay.model.response.PayResponse;
import io.github.atengk.boot.pay.model.response.PayTransferResponse;
import io.github.atengk.boot.pay.util.PayKeyLoader;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.InputStream;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.net.URI;
import java.net.URLConnection;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;
import java.util.function.Supplier;

/**
 * 支付宝专有收单与高阶能力执行门面
 * <p>
 * 基于蚂蚁官方权威 {@code alipay-sdk-java:4.40.1044.ALL} SDK 驱动，具备以下核心工业级特性：
 * <ol>
 *     <li><b>全场景收单覆盖</b>：原生支持电脑网站支付（Page）、手机网站支付（Wap）、App 客户端支付、当面付条码付（Barcode）与扫码预下单（Qr）；</li>
 *     <li><b>全自动凭据封包</b>：Page/Wap 自动生成自提交 HTML 表单脚本（{@code formHtml}），App 自动生成调起签名参数串（{@code orderString}）；</li>
 *     <li><b>双模证书架构自适应</b>：原生无缝支持普通公钥模式与三公钥证书模式（应用公钥证书、支付宝公钥证书、支付宝根证书）；</li>
 *     <li><b>多应用与动态切源</b>：协同 {@link NamedMerchantRegistry} 实现多应用无缝级联与服务商（ISV）{@code app_auth_token} 原生穿透；</li>
 *     <li><b>全域强类型与因果异常转译</b>：入参统一 {@link BigDecimal}（元），底层驱动异常完整保留因果链转译为语义化业务异常；</li>
 *     <li><b>官方客户端逃生舱</b>：随时可通过 {@link #getAlipayClient()} 获取底层原生 {@link AlipayClient} 实例。</li>
 * </ol>
 * </p>
 *
 * @author Ateng
 * @since 2026-10-23
 */
public class AlipayTemplate {

    private static final Logger log = LoggerFactory.getLogger(AlipayTemplate.class);

    private static final String PRODUCT_PAGE_PAY = "FAST_INSTANT_TRADE_PAY";
    private static final String PRODUCT_WAP_PAY = "QUICK_WAP_WAY";
    private static final String PRODUCT_APP_PAY = "QUICK_MSECURITY_PAY";
    private static final String PRODUCT_FACE_TO_FACE = "FACE_TO_FACE_PAYMENT";
    private static final String SCENE_BAR_CODE = "bar_code";

    private final NamedMerchantRegistry namedMerchantRegistry;
    private final PayProperties payProperties;

    /**
     * 支付宝应用客户端持有者缓存：应用别名/AppId -> AlipayClientHolder
     */
    private final ConcurrentHashMap<String, AlipayClientHolder> holderCache = new ConcurrentHashMap<>();

    /**
     * 自定义账单流式下载器（单测脱机钩子）
     */
    private Function<String, InputStream> billStreamDownloader;

    public AlipayTemplate(NamedMerchantRegistry namedMerchantRegistry, PayProperties payProperties) {
        this.namedMerchantRegistry = Objects.requireNonNull(namedMerchantRegistry, "NamedMerchantRegistry 不能为空");
        this.payProperties = payProperties;
    }

    public AlipayTemplate(NamedMerchantRegistry namedMerchantRegistry) {
        this(namedMerchantRegistry, null);
    }

    /**
     * 动态设置单笔资金转账开关（主要供单测使用）
     */
    public void setTransferEnabled(boolean enabled) {
        if (this.payProperties != null && this.payProperties.getTransfer() != null) {
            this.payProperties.getTransfer().setEnabled(enabled);
        }
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
            throw new PayException(PayResultCode.PAY_FEATURE_DISABLED, "支付宝资金转账高敏特性未启用，请配置 ateng.boot.pay.transfer.enabled=true 显式开启");
        }
    }

    // =========================================================================
    // 1. 电脑网站支付 (payPage，自动生成自提交 JavaScript 表单脚本)
    // =========================================================================

    /**
     * 发起支付宝电脑网站支付（PC 端 Web 浏览器跳转收银台）
     *
     * @param request 支付宝收单请求参数
     * @return 统一收单响应对象（formHtml 挂载自提交 HTML 表单脚本）
     */
    public PayResponse payPage(AlipayPayRequest request) {
        validateCheckoutRequest(request);
        AlipayAppProperties appProps = resolveApp(request);
        AlipayClientHolder holder = getClientHolder(appProps);
        String notifyUrl = resolveNotifyUrl(appProps, request);
        String returnUrl = resolveReturnUrl(appProps, request);
        String appAuthToken = resolveAppAuthToken(appProps, request);

        return executeWithExceptionTranslation("payPage", () -> {
            AlipayTradePagePayRequest alipayRequest = new AlipayTradePagePayRequest();
            alipayRequest.setNotifyUrl(notifyUrl);
            alipayRequest.setReturnUrl(returnUrl);

            AlipayTradePagePayModel model = new AlipayTradePagePayModel();
            model.setOutTradeNo(request.getOutTradeNo());
            model.setProductCode(PRODUCT_PAGE_PAY);
            model.setTotalAmount(formatAmount(request.getAmount()));
            model.setSubject(request.getSubject());
            model.setBody(request.getBody());
            model.setTimeExpire(request.getTimeExpire());
            model.setTimeoutExpress(request.getTimeoutExpress());
            model.setQrPayMode(request.getQrPayMode());
            model.setQrcodeWidth(request.getQrcodeWidth());
            model.setPassbackParams(request.getPassbackParams());
            alipayRequest.setBizModel(model);

            if (appAuthToken != null && !appAuthToken.isBlank()) {
                alipayRequest.putOtherTextParam("app_auth_token", appAuthToken);
            }

            AlipayTradePagePayResponse rawResp = holder.alipayClient().pageExecute(alipayRequest, "POST");

            return PayResponse.builder()
                    .channel(PayChannel.ALIPAY)
                    .tradeType(PayTradeType.PAGE)
                    .outTradeNo(request.getOutTradeNo())
                    .amount(request.getAmount())
                    .formHtml(rawResp.getBody())
                    .tradeState(PayTradeState.NOTPAY)
                    .rawTradeState(PayTradeState.NOTPAY.getCode())
                    .tradeStateDesc("等待用户在浏览器中完成支付")
                    .success(true)
                    .rawResponse(rawResp)
                    .build();
        });
    }

    // =========================================================================
    // 2. 手机网站支付 (payWap，自动生成自提交 JavaScript 表单脚本)
    // =========================================================================

    /**
     * 发起支付宝手机网站支付（Wap 手机外部浏览器跳转支付）
     *
     * @param request 支付宝收单请求参数
     * @return 统一收单响应对象（formHtml 挂载自提交 HTML 表单脚本）
     */
    public PayResponse payWap(AlipayPayRequest request) {
        validateCheckoutRequest(request);
        AlipayAppProperties appProps = resolveApp(request);
        AlipayClientHolder holder = getClientHolder(appProps);
        String notifyUrl = resolveNotifyUrl(appProps, request);
        String returnUrl = resolveReturnUrl(appProps, request);
        String appAuthToken = resolveAppAuthToken(appProps, request);

        return executeWithExceptionTranslation("payWap", () -> {
            AlipayTradeWapPayRequest alipayRequest = new AlipayTradeWapPayRequest();
            alipayRequest.setNotifyUrl(notifyUrl);
            alipayRequest.setReturnUrl(returnUrl);

            AlipayTradeWapPayModel model = new AlipayTradeWapPayModel();
            model.setOutTradeNo(request.getOutTradeNo());
            model.setProductCode(PRODUCT_WAP_PAY);
            model.setTotalAmount(formatAmount(request.getAmount()));
            model.setSubject(request.getSubject());
            model.setBody(request.getBody());
            model.setTimeExpire(request.getTimeExpire());
            model.setTimeoutExpress(request.getTimeoutExpress());
            model.setPassbackParams(request.getPassbackParams());
            alipayRequest.setBizModel(model);

            if (appAuthToken != null && !appAuthToken.isBlank()) {
                alipayRequest.putOtherTextParam("app_auth_token", appAuthToken);
            }

            AlipayTradeWapPayResponse rawResp = holder.alipayClient().pageExecute(alipayRequest, "POST");

            return PayResponse.builder()
                    .channel(PayChannel.ALIPAY)
                    .tradeType(PayTradeType.H5)
                    .outTradeNo(request.getOutTradeNo())
                    .amount(request.getAmount())
                    .formHtml(rawResp.getBody())
                    .tradeState(PayTradeState.NOTPAY)
                    .rawTradeState(PayTradeState.NOTPAY.getCode())
                    .tradeStateDesc("等待用户在手机浏览器中完成支付")
                    .success(true)
                    .rawResponse(rawResp)
                    .build();
        });
    }

    // =========================================================================
    // 3. App 支付 (payApp，自动生成已签名的 SDK 调起串 orderString)
    // =========================================================================

    /**
     * 发起支付宝 App 客户端支付，并在服务端完成参数签名与客户端调起串封装
     *
     * @param request 支付宝收单请求参数
     * @return 统一收单响应对象（orderString 挂载可直接传给支付宝 App SDK 的完整加签字符串）
     */
    public PayResponse payApp(AlipayPayRequest request) {
        validateCheckoutRequest(request);
        AlipayAppProperties appProps = resolveApp(request);
        AlipayClientHolder holder = getClientHolder(appProps);
        String notifyUrl = resolveNotifyUrl(appProps, request);
        String appAuthToken = resolveAppAuthToken(appProps, request);

        return executeWithExceptionTranslation("payApp", () -> {
            AlipayTradeAppPayRequest alipayRequest = new AlipayTradeAppPayRequest();
            alipayRequest.setNotifyUrl(notifyUrl);

            AlipayTradeAppPayModel model = new AlipayTradeAppPayModel();
            model.setOutTradeNo(request.getOutTradeNo());
            model.setProductCode(PRODUCT_APP_PAY);
            model.setTotalAmount(formatAmount(request.getAmount()));
            model.setSubject(request.getSubject());
            model.setBody(request.getBody());
            model.setTimeExpire(request.getTimeExpire());
            model.setTimeoutExpress(request.getTimeoutExpress());
            model.setPassbackParams(request.getPassbackParams());
            alipayRequest.setBizModel(model);

            if (appAuthToken != null && !appAuthToken.isBlank()) {
                alipayRequest.putOtherTextParam("app_auth_token", appAuthToken);
            }

            AlipayTradeAppPayResponse rawResp = holder.alipayClient().sdkExecute(alipayRequest);

            return PayResponse.builder()
                    .channel(PayChannel.ALIPAY)
                    .tradeType(PayTradeType.APP)
                    .outTradeNo(request.getOutTradeNo())
                    .amount(request.getAmount())
                    .orderString(rawResp.getBody())
                    .tradeState(PayTradeState.NOTPAY)
                    .rawTradeState(PayTradeState.NOTPAY.getCode())
                    .tradeStateDesc("等待用户在 App 中拉起支付宝完成支付")
                    .success(true)
                    .rawResponse(rawResp)
                    .build();
        });
    }

    // =========================================================================
    // 4. 当面付条码收单 (payBarcode，商户扫描用户付款码被扫)
    // =========================================================================

    /**
     * 发起支付宝当面付条码收单（商户扫描用户支付宝付款码）
     *
     * @param request 支付宝收单请求参数（必须携带 authCode 付款码）
     * @return 统一收单响应对象（若返回 10003 则映射为 USERPAYING 等待用户输入密码）
     */
    public PayResponse payBarcode(AlipayPayRequest request) {
        validateCheckoutRequest(request);
        if (request.getAuthCode() == null || request.getAuthCode().isBlank()) {
            throw new PayException(PayResultCode.PAY_PARAM_INVALID, "条码收单场景必须指定付款码条码 [authCode]");
        }
        AlipayAppProperties appProps = resolveApp(request);
        AlipayClientHolder holder = getClientHolder(appProps);
        String notifyUrl = resolveNotifyUrl(appProps, request);
        String appAuthToken = resolveAppAuthToken(appProps, request);

        return executeWithExceptionTranslation("payBarcode", () -> {
            AlipayTradePayRequest alipayRequest = new AlipayTradePayRequest();
            alipayRequest.setNotifyUrl(notifyUrl);

            AlipayTradePayModel model = new AlipayTradePayModel();
            model.setOutTradeNo(request.getOutTradeNo());
            model.setScene(SCENE_BAR_CODE);
            model.setAuthCode(request.getAuthCode());
            model.setSubject(request.getSubject());
            model.setTotalAmount(formatAmount(request.getAmount()));
            model.setBody(request.getBody());
            model.setTimeoutExpress(request.getTimeoutExpress());
            model.setPassbackParams(request.getPassbackParams());
            alipayRequest.setBizModel(model);

            AlipayTradePayResponse rawResp = executeApi(holder, alipayRequest, appAuthToken);

            if ("10003".equals(rawResp.getCode())) {
                // 10003: 等待用户输入支付密码
                return PayResponse.builder()
                        .channel(PayChannel.ALIPAY)
                        .tradeType(PayTradeType.BARCODE)
                        .outTradeNo(request.getOutTradeNo())
                        .transactionId(rawResp.getTradeNo())
                        .amount(request.getAmount())
                        .tradeState(PayTradeState.USERPAYING)
                        .rawTradeState(rawResp.getCode())
                        .tradeStateDesc("等待用户输入支付密码")
                        .success(true)
                        .rawResponse(rawResp)
                        .build();
            } else if (rawResp.isSuccess() && "10000".equals(rawResp.getCode())) {
                return PayResponse.builder()
                        .channel(PayChannel.ALIPAY)
                        .tradeType(PayTradeType.BARCODE)
                        .outTradeNo(request.getOutTradeNo())
                        .transactionId(rawResp.getTradeNo())
                        .amount(request.getAmount())
                        .tradeState(PayTradeState.SUCCESS)
                        .rawTradeState(rawResp.getCode())
                        .tradeStateDesc("支付成功")
                        .success(true)
                        .rawResponse(rawResp)
                        .build();
            } else {
                throw new PayTradeException(PayResultCode.PAY_TRADE_STATE_INVALID,
                        rawResp.getSubCode() != null ? rawResp.getSubCode() : rawResp.getCode(),
                        rawResp.getSubMsg() != null ? rawResp.getSubMsg() : rawResp.getMsg());
            }
        });
    }

    // =========================================================================
    // 5. 当面付扫码预下单 (payQr，商户生成二维码用户主扫)
    // =========================================================================

    /**
     * 发起支付宝当面付扫码预下单（主扫，生成支付二维码）
     *
     * @param request 支付宝收单请求参数
     * @return 统一收单响应对象（qrCode 挂载二维码内容串）
     */
    public PayResponse payQr(AlipayPayRequest request) {
        validateCheckoutRequest(request);
        AlipayAppProperties appProps = resolveApp(request);
        AlipayClientHolder holder = getClientHolder(appProps);
        String notifyUrl = resolveNotifyUrl(appProps, request);
        String appAuthToken = resolveAppAuthToken(appProps, request);

        return executeWithExceptionTranslation("payQr", () -> {
            AlipayTradePrecreateRequest alipayRequest = new AlipayTradePrecreateRequest();
            alipayRequest.setNotifyUrl(notifyUrl);

            AlipayTradePrecreateModel model = new AlipayTradePrecreateModel();
            model.setOutTradeNo(request.getOutTradeNo());
            model.setSubject(request.getSubject());
            model.setTotalAmount(formatAmount(request.getAmount()));
            model.setBody(request.getBody());
            model.setTimeoutExpress(request.getTimeoutExpress());
            model.setPassbackParams(request.getPassbackParams());
            alipayRequest.setBizModel(model);

            AlipayTradePrecreateResponse rawResp = executeApi(holder, alipayRequest, appAuthToken);

            if (rawResp.isSuccess()) {
                return PayResponse.builder()
                        .channel(PayChannel.ALIPAY)
                        .tradeType(PayTradeType.NATIVE)
                        .outTradeNo(request.getOutTradeNo())
                        .amount(request.getAmount())
                        .qrCode(rawResp.getQrCode())
                        .tradeState(PayTradeState.NOTPAY)
                        .rawTradeState(PayTradeState.NOTPAY.getCode())
                        .tradeStateDesc("等待用户扫码支付")
                        .success(true)
                        .rawResponse(rawResp)
                        .build();
            } else {
                throw new PayTradeException(PayResultCode.PAY_TRADE_STATE_INVALID,
                        rawResp.getSubCode() != null ? rawResp.getSubCode() : rawResp.getCode(),
                        rawResp.getSubMsg() != null ? rawResp.getSubMsg() : rawResp.getMsg());
            }
        });
    }

    // =========================================================================
    // 6. 订单状态查询 (queryOrder)
    // =========================================================================

    /**
     * 主动查询支付宝订单状态并抹平转换为统一交易状态
     *
     * @param request 查询请求参数（outTradeNo 或 tradeNo 必须提供其一）
     * @return 统一交易查询结果响应
     */
    public PayResponse queryOrder(AlipayPayRequest request) {
        validateQueryRequest(request);
        AlipayAppProperties appProps = resolveApp(request);
        AlipayClientHolder holder = getClientHolder(appProps);
        String appAuthToken = resolveAppAuthToken(appProps, request);

        return executeWithExceptionTranslation("queryOrder", () -> {
            AlipayTradeQueryRequest alipayRequest = new AlipayTradeQueryRequest();
            AlipayTradeQueryModel model = new AlipayTradeQueryModel();
            if (request.getOutTradeNo() != null && !request.getOutTradeNo().isBlank()) {
                model.setOutTradeNo(request.getOutTradeNo());
            }
            if (request.getTradeNo() != null && !request.getTradeNo().isBlank()) {
                model.setTradeNo(request.getTradeNo());
            }
            alipayRequest.setBizModel(model);

            AlipayTradeQueryResponse rawResp = executeApi(holder, alipayRequest, appAuthToken);

            if (rawResp.isSuccess()) {
                PayTradeState state = mapTradeState(rawResp.getTradeStatus());
                BigDecimal amount = null;
                if (rawResp.getTotalAmount() != null && !rawResp.getTotalAmount().isBlank()) {
                    try {
                        amount = new BigDecimal(rawResp.getTotalAmount());
                    } catch (NumberFormatException ignored) {
                    }
                }

                return PayResponse.builder()
                        .channel(PayChannel.ALIPAY)
                        .outTradeNo(rawResp.getOutTradeNo() != null ? rawResp.getOutTradeNo() : request.getOutTradeNo())
                        .transactionId(rawResp.getTradeNo())
                        .amount(amount != null ? amount : request.getAmount())
                        .tradeState(state)
                        .rawTradeState(rawResp.getTradeStatus())
                        .tradeStateDesc("TRADE_SUCCESS".equals(rawResp.getTradeStatus()) ? "支付成功" : rawResp.getTradeStatus())
                        .success(state == PayTradeState.SUCCESS)
                        .rawResponse(rawResp)
                        .build();
            } else if ("40004".equals(rawResp.getCode()) && "ACQ.TRADE_NOT_EXIST".equals(rawResp.getSubCode())) {
                throw new PayTradeException(PayResultCode.PAY_ORDER_NOT_FOUND, rawResp.getSubCode(), rawResp.getSubMsg());
            } else {
                throw new PayTradeException(PayResultCode.PAY_TRADE_STATE_INVALID,
                        rawResp.getSubCode() != null ? rawResp.getSubCode() : rawResp.getCode(),
                        rawResp.getSubMsg() != null ? rawResp.getSubMsg() : rawResp.getMsg());
            }
        });
    }

    /**
     * 单标识符重载：根据商户订单号查询默认应用订单
     *
     * @param outTradeNo 商户订单号
     * @return 统一交易查询结果响应
     */
    public PayResponse queryOrder(String outTradeNo) {
        return queryOrder(AlipayPayRequest.builder().outTradeNo(outTradeNo).build());
    }

    /**
     * 单标识符重载：指定应用别名/AppId 查询订单
     *
     * @param appKey     应用别名或 AppId
     * @param outTradeNo 商户订单号
     * @return 统一交易查询结果响应
     */
    public PayResponse queryOrder(String appKey, String outTradeNo) {
        return queryOrder(AlipayPayRequest.builder().appKey(appKey).outTradeNo(outTradeNo).build());
    }

    // =========================================================================
    // 7. 关闭订单 (closeOrder)
    // =========================================================================

    /**
     * 关闭未付款的支付宝订单
     *
     * @param request 关闭请求参数（outTradeNo 或 tradeNo 必须提供其一）
     * @return 关闭操作响应
     */
    public PayResponse closeOrder(AlipayPayRequest request) {
        validateQueryRequest(request);
        AlipayAppProperties appProps = resolveApp(request);
        AlipayClientHolder holder = getClientHolder(appProps);
        String appAuthToken = resolveAppAuthToken(appProps, request);

        return executeWithExceptionTranslation("closeOrder", () -> {
            AlipayTradeCloseRequest alipayRequest = new AlipayTradeCloseRequest();
            AlipayTradeCloseModel model = new AlipayTradeCloseModel();
            if (request.getOutTradeNo() != null && !request.getOutTradeNo().isBlank()) {
                model.setOutTradeNo(request.getOutTradeNo());
            }
            if (request.getTradeNo() != null && !request.getTradeNo().isBlank()) {
                model.setTradeNo(request.getTradeNo());
            }
            alipayRequest.setBizModel(model);

            AlipayTradeCloseResponse rawResp = executeApi(holder, alipayRequest, appAuthToken);

            if (rawResp.isSuccess()) {
                return PayResponse.builder()
                        .channel(PayChannel.ALIPAY)
                        .outTradeNo(rawResp.getOutTradeNo() != null ? rawResp.getOutTradeNo() : request.getOutTradeNo())
                        .transactionId(rawResp.getTradeNo())
                        .tradeState(PayTradeState.CLOSED)
                        .rawTradeState(PayTradeState.CLOSED.getCode())
                        .tradeStateDesc("订单已成功关闭")
                        .success(true)
                        .rawResponse(rawResp)
                        .build();
            } else {
                throw new PayTradeException(PayResultCode.PAY_TRADE_STATE_INVALID,
                        rawResp.getSubCode() != null ? rawResp.getSubCode() : rawResp.getCode(),
                        rawResp.getSubMsg() != null ? rawResp.getSubMsg() : rawResp.getMsg());
            }
        });
    }

    /**
     * 单标识符重载：关闭当前默认应用的未付款订单
     *
     * @param outTradeNo 商户订单号
     * @return 关闭操作响应
     */
    public PayResponse closeOrder(String outTradeNo) {
        return closeOrder(AlipayPayRequest.builder().outTradeNo(outTradeNo).build());
    }

    /**
     * 单标识符重载：指定应用别名/AppId 关闭未付款订单
     *
     * @param appKey     应用别名或 AppId
     * @param outTradeNo 商户订单号
     * @return 关闭操作响应
     */
    public PayResponse closeOrder(String appKey, String outTradeNo) {
        return closeOrder(AlipayPayRequest.builder().appKey(appKey).outTradeNo(outTradeNo).build());
    }

    // =========================================================================
    // 8. 撤销订单 (cancelOrder，当面付超时冲正)
    // =========================================================================

    /**
     * 撤销支付宝当面付订单（支持超时冲正与自动关单/退款）
     *
     * @param request 撤销请求参数（outTradeNo 或 tradeNo 必须提供其一）
     * @return 撤销操作响应
     */
    public PayResponse cancelOrder(AlipayPayRequest request) {
        validateQueryRequest(request);
        AlipayAppProperties appProps = resolveApp(request);
        AlipayClientHolder holder = getClientHolder(appProps);
        String appAuthToken = resolveAppAuthToken(appProps, request);

        return executeWithExceptionTranslation("cancelOrder", () -> {
            AlipayTradeCancelRequest alipayRequest = new AlipayTradeCancelRequest();
            AlipayTradeCancelModel model = new AlipayTradeCancelModel();
            if (request.getOutTradeNo() != null && !request.getOutTradeNo().isBlank()) {
                model.setOutTradeNo(request.getOutTradeNo());
            }
            if (request.getTradeNo() != null && !request.getTradeNo().isBlank()) {
                model.setTradeNo(request.getTradeNo());
            }
            alipayRequest.setBizModel(model);

            AlipayTradeCancelResponse rawResp = executeApi(holder, alipayRequest, appAuthToken);

            if (rawResp.isSuccess()) {
                String action = rawResp.getAction() != null ? rawResp.getAction() : "close";
                return PayResponse.builder()
                        .channel(PayChannel.ALIPAY)
                        .outTradeNo(rawResp.getOutTradeNo() != null ? rawResp.getOutTradeNo() : request.getOutTradeNo())
                        .transactionId(rawResp.getTradeNo())
                        .tradeState(PayTradeState.REVOKED)
                        .rawTradeState(rawResp.getCode())
                        .tradeStateDesc("订单已撤销冲正(action=" + action + ")")
                        .success(true)
                        .rawResponse(rawResp)
                        .build();
            } else {
                throw new PayTradeException(PayResultCode.PAY_TRADE_STATE_INVALID,
                        rawResp.getSubCode() != null ? rawResp.getSubCode() : rawResp.getCode(),
                        rawResp.getSubMsg() != null ? rawResp.getSubMsg() : rawResp.getMsg());
            }
        });
    }

    /**
     * 单标识符重载：撤销当前默认应用的当面付订单
     *
     * @param outTradeNo 商户订单号
     * @return 撤销操作响应
     */
    public PayResponse cancelOrder(String outTradeNo) {
        return cancelOrder(AlipayPayRequest.builder().outTradeNo(outTradeNo).build());
    }

    /**
     * 单标识符重载：指定应用别名/AppId 撤销订单
     *
     * @param appKey     应用别名或 AppId
     * @param outTradeNo 商户订单号
     * @return 撤销操作响应
     */
    public PayResponse cancelOrder(String appKey, String outTradeNo) {
        return cancelOrder(AlipayPayRequest.builder().appKey(appKey).outTradeNo(outTradeNo).build());
    }

    // =========================================================================
    // 9. 统一退款申请 (refund)
    // =========================================================================

    /**
     * 发起支付宝交易退款（支持多次部分退款与全额退款）
     * <p>
     * 自动将商户退款单号 {@code outRefundNo} 映射为支付宝专有的 {@code out_request_no}，
     * 杜绝同一订单多次部分退款被误判为重复提交而被拦截。
     * </p>
     *
     * @param request 支付宝退款请求参数
     * @return 统一退款响应出参
     */
    public PayRefundResponse refund(AlipayRefundRequest request) {
        validateRefundRequest(request);
        AlipayAppProperties appProps = resolveApp(request.getAppKey());
        AlipayClientHolder holder = getClientHolder(appProps);
        String appAuthToken = resolveAppAuthToken(appProps, request.getAppAuthToken());

        return executeWithExceptionTranslation("refund", () -> {
            AlipayTradeRefundRequest alipayRequest = new AlipayTradeRefundRequest();
            if (request.getNotifyUrl() != null && !request.getNotifyUrl().isBlank()) {
                alipayRequest.setNotifyUrl(request.getNotifyUrl());
            }

            AlipayTradeRefundModel model = new AlipayTradeRefundModel();
            if (request.getOutTradeNo() != null && !request.getOutTradeNo().isBlank()) {
                model.setOutTradeNo(request.getOutTradeNo());
            }
            if (request.getTradeNo() != null && !request.getTradeNo().isBlank()) {
                model.setTradeNo(request.getTradeNo());
            }
            model.setRefundAmount(formatAmount(request.getRefundAmount()));
            model.setRefundReason(request.getReason());
            model.setOutRequestNo(request.getOutRefundNo()); // 关键四要素映射
            alipayRequest.setBizModel(model);

            AlipayTradeRefundResponse rawResp = executeApi(holder, alipayRequest, appAuthToken);

            if (rawResp.isSuccess()) {
                BigDecimal refundFee = null;
                if (rawResp.getRefundFee() != null && !rawResp.getRefundFee().isBlank()) {
                    refundFee = new BigDecimal(rawResp.getRefundFee());
                }
                if (refundFee == null) {
                    refundFee = request.getRefundAmount();
                }

                return PayRefundResponse.builder()
                        .channel(PayChannel.ALIPAY)
                        .outTradeNo(rawResp.getOutTradeNo() != null ? rawResp.getOutTradeNo() : request.getOutTradeNo())
                        .transactionId(rawResp.getTradeNo())
                        .outRefundNo(request.getOutRefundNo())
                        .totalAmount(request.getTotalAmount())
                        .refundAmount(refundFee)
                        .refundState(PayRefundState.SUCCESS)
                        .rawRefundState(rawResp.getCode())
                        .refundStateDesc(rawResp.getMsg() != null ? rawResp.getMsg() : "退款成功")
                        .success(true)
                        .rawResponse(rawResp)
                        .build();
            } else {
                throw new PayTradeException(PayResultCode.PAY_TRADE_STATE_INVALID,
                        rawResp.getSubCode() != null ? rawResp.getSubCode() : rawResp.getCode(),
                        rawResp.getSubMsg() != null ? rawResp.getSubMsg() : rawResp.getMsg());
            }
        });
    }

    /**
     * 基于顶层统一退款请求模型发起支付宝退款
     *
     * @param request 顶层统一退款请求入参
     * @return 统一退款响应出参
     */
    public PayRefundResponse refund(UnifiedRefundRequest request) {
        if (request == null) {
            throw new PayException(PayResultCode.PAY_PARAM_INVALID, "统一退款请求入参不能为空");
        }
        request.validate();
        return refund(AlipayRefundRequest.builder()
                .appKey(request.getMerchantKey())
                .outTradeNo(request.getOutTradeNo())
                .tradeNo(request.getTransactionId())
                .outRefundNo(request.getOutRefundNo())
                .totalAmount(request.getTotalAmount())
                .refundAmount(request.getRefundAmount())
                .reason(request.getReason())
                .notifyUrl(request.getNotifyUrl())
                .appAuthToken(request.getAppAuthToken())
                .build());
    }

    // =========================================================================
    // 10. 统一退款查询 (queryRefund)
    // =========================================================================

    /**
     * 查询支付宝单笔退款状态
     *
     * @param request 支付宝退款查询参数
     * @return 统一退款响应出参
     */
    public PayRefundResponse queryRefund(AlipayRefundRequest request) {
        if (request.getOutRefundNo() == null || request.getOutRefundNo().isBlank()) {
            throw new PayException(PayResultCode.PAY_PARAM_INVALID, "查询支付宝退款必须提供商户退款单号 [outRefundNo]");
        }
        if ((request.getOutTradeNo() == null || request.getOutTradeNo().isBlank())
                && (request.getTradeNo() == null || request.getTradeNo().isBlank())) {
            throw new PayException(PayResultCode.PAY_PARAM_INVALID, "查询支付宝退款必须提供商户原订单号 [outTradeNo] 或支付宝交易号 [tradeNo]");
        }
        AlipayAppProperties appProps = resolveApp(request.getAppKey());
        AlipayClientHolder holder = getClientHolder(appProps);
        String appAuthToken = resolveAppAuthToken(appProps, request.getAppAuthToken());

        return executeWithExceptionTranslation("queryRefund", () -> {
            AlipayTradeFastpayRefundQueryRequest alipayRequest = new AlipayTradeFastpayRefundQueryRequest();
            AlipayTradeFastpayRefundQueryModel model = new AlipayTradeFastpayRefundQueryModel();
            if (request.getOutTradeNo() != null && !request.getOutTradeNo().isBlank()) {
                model.setOutTradeNo(request.getOutTradeNo());
            }
            if (request.getTradeNo() != null && !request.getTradeNo().isBlank()) {
                model.setTradeNo(request.getTradeNo());
            }
            model.setOutRequestNo(request.getOutRefundNo());
            alipayRequest.setBizModel(model);

            AlipayTradeFastpayRefundQueryResponse rawResp = executeApi(holder, alipayRequest, appAuthToken);

            if (rawResp.isSuccess()) {
                BigDecimal totalAmount = null;
                BigDecimal refundAmount = null;
                if (rawResp.getTotalAmount() != null && !rawResp.getTotalAmount().isBlank()) {
                    totalAmount = new BigDecimal(rawResp.getTotalAmount());
                }
                if (rawResp.getRefundAmount() != null && !rawResp.getRefundAmount().isBlank()) {
                    refundAmount = new BigDecimal(rawResp.getRefundAmount());
                }
                if (totalAmount == null) {
                    totalAmount = request.getTotalAmount();
                }
                if (refundAmount == null) {
                    refundAmount = request.getRefundAmount();
                }

                PayRefundState state = mapRefundState(rawResp.getRefundStatus());
                return PayRefundResponse.builder()
                        .channel(PayChannel.ALIPAY)
                        .outTradeNo(rawResp.getOutTradeNo() != null ? rawResp.getOutTradeNo() : request.getOutTradeNo())
                        .transactionId(rawResp.getTradeNo() != null ? rawResp.getTradeNo() : request.getTradeNo())
                        .outRefundNo(rawResp.getOutRequestNo() != null ? rawResp.getOutRequestNo() : request.getOutRefundNo())
                        .totalAmount(totalAmount)
                        .refundAmount(refundAmount)
                        .refundState(state)
                        .rawRefundState(rawResp.getRefundStatus() != null ? rawResp.getRefundStatus() : rawResp.getCode())
                        .refundStateDesc(rawResp.getRefundReason() != null ? rawResp.getRefundReason() : "退款成功")
                        .success(true)
                        .rawResponse(rawResp)
                        .build();
            } else {
                throw new PayTradeException(PayResultCode.PAY_TRADE_STATE_INVALID,
                        rawResp.getSubCode() != null ? rawResp.getSubCode() : rawResp.getCode(),
                        rawResp.getSubMsg() != null ? rawResp.getSubMsg() : rawResp.getMsg());
            }
        });
    }

    /**
     * 单标识符重载：查询当前默认应用的退款状态
     *
     * @param outTradeNo  商户原订单号
     * @param outRefundNo 商户退款单号
     * @return 统一退款响应出参
     */
    public PayRefundResponse queryRefund(String outTradeNo, String outRefundNo) {
        return queryRefund(AlipayRefundRequest.builder().outTradeNo(outTradeNo).outRefundNo(outRefundNo).build());
    }

    /**
     * 单标识符重载：指定应用别名查询退款状态
     *
     * @param appKey      应用别名或 AppId
     * @param outTradeNo  商户原订单号
     * @param outRefundNo 商户退款单号
     * @return 统一退款响应出参
     */
    public PayRefundResponse queryRefund(String appKey, String outTradeNo, String outRefundNo) {
        return queryRefund(AlipayRefundRequest.builder().appKey(appKey).outTradeNo(outTradeNo).outRefundNo(outRefundNo).build());
    }

    /**
     * 将支付宝原生退款状态映射为统一退款状态枚举
     *
     * @param alipayRefundStatus 支付宝 refund_status 字符串
     * @return 标准化统一退款状态
     */
    public static PayRefundState mapRefundState(String alipayRefundStatus) {
        if (alipayRefundStatus == null || alipayRefundStatus.isBlank()) {
            return PayRefundState.SUCCESS;
        }
        if ("REFUND_SUCCESS".equalsIgnoreCase(alipayRefundStatus.trim())) {
            return PayRefundState.SUCCESS;
        }
        return PayRefundState.PROCESSING;
    }

    private void validateRefundRequest(AlipayRefundRequest request) {
        if (request == null) {
            throw new PayException(PayResultCode.PAY_PARAM_INVALID, "支付宝退款请求入参不能为空");
        }
        request.validate();
    }

    // =========================================================================
    // 10. 单笔资金转账 (transfer)
    // =========================================================================

    /**
     * 发起支付宝单笔资金转账（到支付宝账户或银行卡）
     *
     * @param appKey  应用别名或 AppId（可选）
     * @param request 支付宝转账专有请求参数
     * @return 统一转账响应出参
     */
    public PayTransferResponse transfer(String appKey, AlipayTransferRequest request) {
        assertTransferEnabled();
        validateTransferRequest(request);

        String key = (appKey != null && !appKey.isBlank()) ? appKey : request.getAppKey();
        AlipayAppProperties appProps = resolveApp(key);
        AlipayClientHolder holder = getClientHolder(appProps);

        AlipayFundTransUniTransferRequest alipayRequest = new AlipayFundTransUniTransferRequest();
        AlipayFundTransUniTransferModel model = new AlipayFundTransUniTransferModel();

        model.setOutBizNo(request.getOutBizNo());
        model.setTransAmount(formatAmount(request.getAmount()));
        model.setProductCode(request.getProductCode() != null && !request.getProductCode().isBlank()
                ? request.getProductCode()
                : "TRANS_ACCOUNT_NO_PWD");
        model.setBizScene(request.getBizScene() != null && !request.getBizScene().isBlank()
                ? request.getBizScene()
                : "DIRECT_TRANSFER");
        model.setOrderTitle(request.getOrderTitle() != null && !request.getOrderTitle().isBlank()
                ? request.getOrderTitle()
                : "单笔转账");
        if (request.getRemark() != null && !request.getRemark().isBlank()) {
            model.setRemark(request.getRemark());
        }

        Participant payeeInfo = new Participant();
        payeeInfo.setIdentity(request.getPayeeAccount());
        payeeInfo.setIdentityType(request.getPayeeType() != null && !request.getPayeeType().isBlank()
                ? request.getPayeeType()
                : "ALIPAY_LOGON_ID");
        if (request.getPayeeRealName() != null && !request.getPayeeRealName().isBlank()) {
            payeeInfo.setName(request.getPayeeRealName());
        }
        model.setPayeeInfo(payeeInfo);

        alipayRequest.setBizModel(model);

        log.debug("Initiating Alipay single transfer: outBizNo={}, amount={}, payeeAccount={}, payeeRealName={}",
                request.getOutBizNo(), request.getAmount(),
                DesensitizeUtil.maskKeepHeadTail(request.getPayeeAccount(), 2, 2),
                DesensitizeUtil.chineseName(request.getPayeeRealName()));

        AlipayFundTransUniTransferResponse response = executeWithExceptionTranslation("transfer", () -> {
            try {
                return executeApi(holder, alipayRequest, resolveAppAuthToken(appProps, (String) null));
            } catch (AlipayApiException e) {
                throw new PayException(PayResultCode.PAY_GATEWAY_ERROR, "调用支付宝单笔转账接口通信失败: " + e.getMessage(), e);
            }
        });

        if (!response.isSuccess()) {
            throw new PayTradeException(PayResultCode.PAY_GATEWAY_ERROR,
                    response.getSubCode() != null ? response.getSubCode() : response.getCode(),
                    response.getSubMsg() != null ? response.getSubMsg() : response.getMsg());
        }

        PayTransferState state = mapAlipayTransferStatus(response.getStatus());

        return PayTransferResponse.builder()
                .channel(PayChannel.ALIPAY)
                .outBizNo(response.getOutBizNo() != null ? response.getOutBizNo() : request.getOutBizNo())
                .orderId(response.getOrderId())
                .payFundOrderId(response.getPayFundOrderId())
                .status(state)
                .amount(request.getAmount())
                .payeeAccount(request.getPayeeAccount())
                .payeeRealName(request.getPayeeRealName())
                .payTime(response.getTransDate())
                .rawResponse(response)
                .build();
    }

    /**
     * 发起支付宝单笔资金转账（默认应用）
     *
     * @param request 支付宝转账专有请求参数
     * @return 统一转账响应出参
     */
    public PayTransferResponse transfer(AlipayTransferRequest request) {
        return transfer(null, request);
    }

    /**
     * 查询支付宝单笔转账状态
     *
     * @param appKey   应用别名或 AppId（可选）
     * @param outBizNo 商家唯一转账订单号
     * @param orderId  支付宝转账单据号（可选）
     * @return 统一转账响应出参
     */
    public PayTransferResponse queryTransfer(String appKey, String outBizNo, String orderId) {
        assertTransferEnabled();
        if ((outBizNo == null || outBizNo.isBlank()) && (orderId == null || orderId.isBlank())) {
            throw new PayException(PayResultCode.PAY_PARAM_INVALID, "商户转账单号 [outBizNo] 与支付宝转账单号 [orderId] 不能同时为空");
        }

        AlipayAppProperties appProps = resolveApp(appKey);
        AlipayClientHolder holder = getClientHolder(appProps);

        AlipayFundTransCommonQueryRequest queryRequest = new AlipayFundTransCommonQueryRequest();
        AlipayFundTransCommonQueryModel model = new AlipayFundTransCommonQueryModel();
        model.setBizScene("DIRECT_TRANSFER");
        model.setProductCode("TRANS_ACCOUNT_NO_PWD");
        if (outBizNo != null && !outBizNo.isBlank()) {
            model.setOutBizNo(outBizNo);
        }
        if (orderId != null && !orderId.isBlank()) {
            model.setOrderId(orderId);
        }
        queryRequest.setBizModel(model);

        AlipayFundTransCommonQueryResponse response = executeWithExceptionTranslation("queryTransfer", () -> {
            try {
                return executeApi(holder, queryRequest, resolveAppAuthToken(appProps, (String) null));
            } catch (AlipayApiException e) {
                throw new PayException(PayResultCode.PAY_GATEWAY_ERROR, "调用支付宝转账查询接口通信失败: " + e.getMessage(), e);
            }
        });

        if (!response.isSuccess()) {
            throw new PayTradeException(PayResultCode.PAY_GATEWAY_ERROR,
                    response.getSubCode() != null ? response.getSubCode() : response.getCode(),
                    response.getSubMsg() != null ? response.getSubMsg() : response.getMsg());
        }

        PayTransferState state = mapAlipayTransferStatus(response.getStatus());

        BigDecimal fee = null;
        if (response.getOrderFee() != null && !response.getOrderFee().isBlank()) {
            try {
                fee = new BigDecimal(response.getOrderFee());
            } catch (Exception ignored) {
            }
        }

        return PayTransferResponse.builder()
                .channel(PayChannel.ALIPAY)
                .outBizNo(response.getOutBizNo())
                .orderId(response.getOrderId())
                .payFundOrderId(response.getPayFundOrderId())
                .status(state)
                .amount(fee)
                .payeeAccount(response.getReceiverOpenId() != null ? response.getReceiverOpenId() : response.getReceiverUserId())
                .payTime(response.getPayDate() != null ? response.getPayDate() : response.getArrivalTimeEnd())
                .failReason(response.getFailReason())
                .rawResponse(response)
                .build();
    }

    /**
     * 查询支付宝单笔转账状态（默认应用）
     *
     * @param outBizNo 商家唯一转账订单号
     * @param orderId  支付宝转账单据号（可选）
     * @return 统一转账响应出参
     */
    public PayTransferResponse queryTransfer(String outBizNo, String orderId) {
        return queryTransfer(null, outBizNo, orderId);
    }

    /**
     * 查询支付宝单笔转账状态（仅凭商户订单号，默认应用）
     *
     * @param outBizNo 商家唯一转账订单号
     * @return 统一转账响应出参
     */
    public PayTransferResponse queryTransfer(String outBizNo) {
        return queryTransfer(null, outBizNo, null);
    }

    /**
     * 将支付宝原生转账单据状态映射为统一转账状态机
     *
     * @param status 支付宝 status 字符串
     * @return 标准化转账状态
     */
    public static PayTransferState mapAlipayTransferStatus(String status) {
        if (status == null || status.isBlank()) {
            return PayTransferState.SUCCESS;
        }
        return switch (status.trim().toUpperCase()) {
            case "SUCCESS" -> PayTransferState.SUCCESS;
            case "FAIL" -> PayTransferState.FAIL;
            case "DEALING", "WAIT_PAY" -> PayTransferState.PROCESSING;
            case "REFUND" -> PayTransferState.REFUND;
            case "CLOSED" -> PayTransferState.CLOSED;
            default -> PayTransferState.PROCESSING;
        };
    }

    private void validateTransferRequest(AlipayTransferRequest request) {
        if (request == null) {
            throw new PayException(PayResultCode.PAY_PARAM_INVALID, "支付宝转账请求入参不能为空");
        }
        if (request.getOutBizNo() == null || request.getOutBizNo().isBlank()) {
            throw new PayException(PayResultCode.PAY_PARAM_INVALID, "商户转账唯一单号 [outBizNo] 不能为空");
        }
        if (request.getAmount() == null || request.getAmount().compareTo(BigDecimal.ZERO) <= 0) {
            throw new PayException(PayResultCode.PAY_AMOUNT_INVALID, "转账金额 [amount] 必须大于 0 元");
        }
        BigDecimal stripped = request.getAmount().stripTrailingZeros();
        if (stripped.scale() > 2) {
            throw new PayException(PayResultCode.PAY_AMOUNT_INVALID,
                    "转账金额小数位不能超过 2 位（分）：" + request.getAmount());
        }
        if (request.getPayeeAccount() == null || request.getPayeeAccount().isBlank()) {
            throw new PayException(PayResultCode.PAY_PARAM_INVALID, "收款人账号 [payeeAccount] 不能为空");
        }
    }

    // =========================================================================
    // 11. 财务对账单下载 (Financial Bill Download)
    // =========================================================================

    /**
     * 查询账单下载地址（返回 30s 内有效的直链 URL）
     *
     * @param billType 账单类型：trade（交易账单）、signcustomer（账务账单）
     * @param billDate 账单日期（日账单格式 yyyy-MM-dd，月账单格式 yyyy-MM）
     * @return 官方查询响应（含 billDownloadUrl 与 billFileCode）
     */
    public AlipayDataDataserviceBillDownloadurlQueryResponse queryBillDownloadUrl(String billType, String billDate) {
        return queryBillDownloadUrl(billType, billDate, null);
    }

    /**
     * 查询账单下载地址（指定应用/商户标识）
     *
     * @param billType    账单类型
     * @param billDate    账单日期
     * @param merchantKey 多商户标识
     * @return 官方查询响应
     */
    public AlipayDataDataserviceBillDownloadurlQueryResponse queryBillDownloadUrl(String billType, String billDate, String merchantKey) {
        if (billDate == null || billDate.isBlank()) {
            throw new PayException(PayResultCode.PAY_PARAM_INVALID, "支付宝对账单日期 [billDate] 不能为空");
        }
        String realBillType = (billType != null && !billType.isBlank()) ? billType : "trade";
        AlipayAppProperties appProps = resolveApp(merchantKey);
        AlipayClientHolder holder = getClientHolder(appProps);

        return executeWithExceptionTranslation("queryBillDownloadUrl", () -> {
            AlipayDataDataserviceBillDownloadurlQueryRequest alipayRequest = new AlipayDataDataserviceBillDownloadurlQueryRequest();
            AlipayDataDataserviceBillDownloadurlQueryModel model = new AlipayDataDataserviceBillDownloadurlQueryModel();
            model.setBillType(realBillType);
            model.setBillDate(billDate);
            alipayRequest.setBizModel(model);

            AlipayDataDataserviceBillDownloadurlQueryResponse response = executeApi(holder, alipayRequest, null);
            if (!response.isSuccess()) {
                throw new PayTradeException(PayResultCode.PAY_GATEWAY_ERROR,
                        response.getSubCode() != null ? response.getSubCode() : response.getCode(),
                        response.getSubMsg() != null ? response.getSubMsg() : response.getMsg());
            }
            return response;
        });
    }

    /**
     * 获取账单下载链接 URL
     *
     * @param billType 账单类型 (trade / signcustomer)
     * @param billDate 账单日期
     * @return 30s 有效的下载地址
     */
    public String getBillDownloadUrl(String billType, String billDate) {
        return getBillDownloadUrl(billType, billDate, null);
    }

    /**
     * 获取账单下载链接 URL（指定商户）
     */
    public String getBillDownloadUrl(String billType, String billDate, String merchantKey) {
        return queryBillDownloadUrl(billType, billDate, merchantKey).getBillDownloadUrl();
    }

    /**
     * 直接下载账单 ZIP 字节输入流（一步到位）
     *
     * @param billType 账单类型 (trade / signcustomer)
     * @param billDate 账单日期 (yyyy-MM-dd)
     * @return 账单 ZIP 输入流
     */
    public InputStream downloadBillStream(String billType, String billDate) {
        return downloadBillStream(billType, billDate, null);
    }

    /**
     * 直接下载账单 ZIP 字节输入流（指定商户）
     */
    public InputStream downloadBillStream(String billType, String billDate, String merchantKey) {
        String downloadUrl = getBillDownloadUrl(billType, billDate, merchantKey);
        return downloadBillStream(downloadUrl);
    }

    /**
     * 根据任意直链下载地址拉取输入流
     *
     * @param downloadUrl 账单下载地址
     * @return 字节流
     */
    public InputStream downloadBillStream(String downloadUrl) {
        if (downloadUrl == null || downloadUrl.isBlank()) {
            throw new PayException(PayResultCode.PAY_PARAM_INVALID, "支付宝对账单下载地址不能为空");
        }
        if (billStreamDownloader != null) {
            return billStreamDownloader.apply(downloadUrl);
        }
        return executeWithExceptionTranslation("downloadBillStream", () -> {
            URI uri = URI.create(downloadUrl);
            URLConnection conn = uri.toURL().openConnection();
            conn.setConnectTimeout(15000);
            conn.setReadTimeout(60000);
            return conn.getInputStream();
        });
    }

    /**
     * 注入自定义对账单流式下载器（主要供单元测试脱机离线使用）
     */
    public void setBillStreamDownloader(Function<String, InputStream> billStreamDownloader) {
        this.billStreamDownloader = billStreamDownloader;
    }

    // =========================================================================
    // 12. 状态机与模型映射辅助工具 (Normalization)
    // =========================================================================

    /**
     * 将支付宝原生交易状态转换为统一交易状态枚举
     *
     * @param alipayTradeStatus 支付宝 trade_status 字符串
     * @return 标准化交易生命周期状态
     */
    public static PayTradeState mapTradeState(String alipayTradeStatus) {
        if (alipayTradeStatus == null || alipayTradeStatus.isBlank()) {
            return PayTradeState.NOTPAY;
        }
        return switch (alipayTradeStatus.trim()) {
            case "TRADE_SUCCESS", "TRADE_FINISHED" -> PayTradeState.SUCCESS;
            case "WAIT_BUYER_PAY" -> PayTradeState.NOTPAY;
            case "TRADE_CLOSED" -> PayTradeState.CLOSED;
            default -> PayTradeState.NOTPAY;
        };
    }

    // =========================================================================
    // 10. 官方 SDK 客户端逃生舱与定制替换
    // =========================================================================

    /**
     * 获取当前默认应用的官方原生 AlipayClient 实例
     */
    public AlipayClient getAlipayClient() {
        return getClientHolder(namedMerchantRegistry.resolveAlipayApp(null)).alipayClient();
    }

    /**
     * 统一客户端逃生舱别名：获取当前默认应用的官方原生 AlipayClient 实例
     */
    public AlipayClient getClient() {
        return getAlipayClient();
    }

    /**
     * 获取指定应用配置键/AppId 对应的官方原生 AlipayClient 实例
     *
     * @param appKey 应用配置键或 AppId
     */
    public AlipayClient getAlipayClient(String appKey) {
        return getClientHolder(namedMerchantRegistry.resolveAlipayApp(appKey)).alipayClient();
    }

    /**
     * 统一客户端逃生舱别名：获取指定应用配置键/AppId 对应的官方原生 AlipayClient 实例
     *
     * @param appKey 应用配置键或 AppId
     */
    public AlipayClient getClient(String appKey) {
        return getAlipayClient(appKey);
    }

    /**
     * 获取指定应用配置键/AppId 对应的配置属性
     *
     * @param appKey 应用配置键或 AppId
     */
    public AlipayAppProperties getAppProperties(String appKey) {
        return namedMerchantRegistry.resolveAlipayApp(appKey);
    }

    /**
     * 设置自定义 AlipayClient 实例（供单元测试 Mock 或外部客户端代理扩展）
     *
     * @param appKey       应用别名或 AppId
     * @param customClient 自定义客户端实例
     */
    public void setCustomAlipayClient(String appKey, AlipayClient customClient) {
        AlipayAppProperties appProps = namedMerchantRegistry.resolveAlipayApp(appKey);
        holderCache.put(appKey, new AlipayClientHolder(appProps, customClient, appProps.isCertMode()));
        if (appProps.getAppId() != null) {
            holderCache.put(appProps.getAppId(), new AlipayClientHolder(appProps, customClient, appProps.isCertMode()));
        }
    }

    // =========================================================================
    // 内部私有辅助逻辑
    // =========================================================================

    private AlipayAppProperties resolveApp(AlipayPayRequest request) {
        String key = (request != null && request.getAppKey() != null && !request.getAppKey().isBlank())
                ? request.getAppKey()
                : (request != null ? request.getAppId() : null);
        return resolveApp(key);
    }

    private AlipayAppProperties resolveApp(String appKey) {
        String key = (appKey != null && !appKey.isBlank()) ? appKey : PayContextHolder.getMerchantId();
        return namedMerchantRegistry.resolveAlipayApp(key);
    }

    private AlipayClientHolder getClientHolder(AlipayAppProperties properties) {
        String cacheKey = properties.getAppId() != null ? properties.getAppId() : "default";
        return holderCache.computeIfAbsent(cacheKey, k -> createClientHolder(properties));
    }

    private AlipayClientHolder createClientHolder(AlipayAppProperties properties) {
        AlipayConfig config = new AlipayConfig();
        config.setServerUrl(properties.getServerUrl());
        config.setAppId(properties.getAppId());
        config.setFormat(properties.getFormat() != null ? properties.getFormat() : "json");
        config.setCharset(properties.getCharset() != null ? properties.getCharset() : "UTF-8");
        config.setSignType(properties.getSignType() != null ? properties.getSignType() : "RSA2");

        // 1. 加载商户私钥（自适应四模：PEM 行内、file:、classpath:、SPI）
        String privateKeyPem;
        if (properties.getPrivateKey() != null && !properties.getPrivateKey().isBlank()) {
            privateKeyPem = PayKeyLoader.loadPrivateKeyPem(properties.getPrivateKey());
        } else if (properties.getPrivateKeyPath() != null && !properties.getPrivateKeyPath().isBlank()) {
            privateKeyPem = PayKeyLoader.loadPrivateKeyPem(properties.getPrivateKeyPath());
        } else {
            throw new PayException(PayResultCode.PAY_PARAM_INVALID,
                    "支付宝应用未配置私钥 privateKey 或 privateKeyPath: " + properties.getAppId());
        }
        config.setPrivateKey(privateKeyPem);

        // 2. 区分公钥模式与三证书模式
        boolean isCertMode = properties.isCertMode();
        if (isCertMode) {
            if (properties.getAppCertPath() != null && !properties.getAppCertPath().isBlank()) {
                config.setAppCertContent(PayKeyLoader.loadString(properties.getAppCertPath()));
            }
            if (properties.getAlipayCertPath() != null && !properties.getAlipayCertPath().isBlank()) {
                config.setAlipayPublicCertContent(PayKeyLoader.loadString(properties.getAlipayCertPath()));
            }
            if (properties.getAlipayRootCertPath() != null && !properties.getAlipayRootCertPath().isBlank()) {
                config.setRootCertContent(PayKeyLoader.loadString(properties.getAlipayRootCertPath()));
            }
        } else {
            if (properties.getAlipayPublicKey() != null && !properties.getAlipayPublicKey().isBlank()) {
                config.setAlipayPublicKey(PayKeyLoader.loadPublicKeyPem(properties.getAlipayPublicKey()));
            }
        }

        try {
            DefaultAlipayClient client = new DefaultAlipayClient(config);
            return new AlipayClientHolder(properties, client, isCertMode);
        } catch (AlipayApiException e) {
            throw new PayException(PayResultCode.PAY_PARAM_INVALID,
                    "初始化支付宝客户端失败: " + e.getMessage(), e);
        }
    }

    private <T extends AlipayResponse> T executeApi(AlipayClientHolder holder, AlipayRequest<T> request, String appAuthToken) throws AlipayApiException {
        if (holder.certMode()) {
            return holder.alipayClient().certificateExecute(request, null, appAuthToken);
        } else {
            return holder.alipayClient().execute(request, null, appAuthToken);
        }
    }

    private String resolveNotifyUrl(AlipayAppProperties props, AlipayPayRequest request) {
        if (request != null && request.getNotifyUrl() != null && !request.getNotifyUrl().isBlank()) {
            return request.getNotifyUrl();
        }
        return props.getNotifyUrl();
    }

    private String resolveReturnUrl(AlipayAppProperties props, AlipayPayRequest request) {
        if (request != null && request.getReturnUrl() != null && !request.getReturnUrl().isBlank()) {
            return request.getReturnUrl();
        }
        return props.getReturnUrl();
    }

    private String resolveAppAuthToken(AlipayAppProperties props, AlipayPayRequest request) {
        if (request != null && request.getAppAuthToken() != null && !request.getAppAuthToken().isBlank()) {
            return request.getAppAuthToken();
        }
        return props.getAppAuthToken();
    }

    private String resolveAppAuthToken(AlipayAppProperties props, String appAuthToken) {
        if (appAuthToken != null && !appAuthToken.isBlank()) {
            return appAuthToken;
        }
        return props.getAppAuthToken();
    }

    private String formatAmount(BigDecimal amount) {
        return amount.setScale(2, RoundingMode.HALF_UP).toPlainString();
    }

    private void validateCheckoutRequest(AlipayPayRequest request) {
        if (request == null) {
            throw new PayException(PayResultCode.PAY_PARAM_INVALID, "支付宝支付请求参数不能为空");
        }
        if (request.getOutTradeNo() == null || request.getOutTradeNo().isBlank()) {
            throw new PayException(PayResultCode.PAY_PARAM_INVALID, "商户订单号 [outTradeNo] 不能为空");
        }
        if (request.getSubject() == null || request.getSubject().isBlank()) {
            throw new PayException(PayResultCode.PAY_PARAM_INVALID, "订单标题 [subject] 不能为空");
        }
        if (request.getAmount() == null || request.getAmount().compareTo(BigDecimal.ZERO) <= 0) {
            throw new PayException(PayResultCode.PAY_AMOUNT_INVALID, "订单金额 [amount] 必须大于 0 元");
        }
    }

    private void validateQueryRequest(AlipayPayRequest request) {
        if (request == null) {
            throw new PayException(PayResultCode.PAY_PARAM_INVALID, "支付宝订单操作请求不能为空");
        }
        boolean hasOutTradeNo = request.getOutTradeNo() != null && !request.getOutTradeNo().isBlank();
        boolean hasTradeNo = request.getTradeNo() != null && !request.getTradeNo().isBlank();
        if (!hasOutTradeNo && !hasTradeNo) {
            throw new PayException(PayResultCode.PAY_PARAM_INVALID, "必须提供商户订单号 [outTradeNo] 或支付宝交易号 [tradeNo]");
        }
    }

    private <R> R executeWithExceptionTranslation(String operationName, ThrowingSupplier<R> action) {
        try {
            return action.get();
        } catch (PayException e) {
            throw e;
        } catch (Exception e) {
            AlipayApiException apiEx = findCause(e, AlipayApiException.class);
            String errorMsg = e.getMessage() != null ? e.getMessage() : (apiEx != null ? apiEx.getMessage() : "");

            // 签名与验签异常拦截
            if (errorMsg.contains("sign") || errorMsg.contains("CHECK_CERT") || errorMsg.contains("验签")
                    || hasCause(e, java.security.SignatureException.class)) {
                throw new PaySignException(PayResultCode.PAY_SIGN_VERIFY_ERROR,
                        "支付宝签名验证失败 [" + operationName + "]: " + errorMsg, e);
            }

            // 网络异常拦截
            if (hasCause(e, IOException.class) || errorMsg.contains("timeout") || errorMsg.contains("Connect")) {
                throw new PayNetworkException("支付宝网关网络通信异常 [" + operationName + "]: " + errorMsg, e);
            }

            // 渠道业务异常拦截
            if (apiEx != null) {
                throw new PayTradeException(PayResultCode.PAY_TRADE_STATE_INVALID,
                        apiEx.getErrCode(),
                        apiEx.getErrMsg() != null ? apiEx.getErrMsg() : errorMsg,
                        apiEx);
            }

            throw new PayException(PayResultCode.PAY_GATEWAY_ERROR,
                    "执行支付宝操作异常 [" + operationName + "]: " + errorMsg, e);
        }
    }

    private <T extends Throwable> T findCause(Throwable throwable, Class<T> targetClass) {
        Throwable current = throwable;
        while (current != null) {
            if (targetClass.isInstance(current)) {
                return targetClass.cast(current);
            }
            current = current.getCause();
        }
        return null;
    }

    private boolean hasCause(Throwable throwable, Class<? extends Throwable> targetClass) {
        return findCause(throwable, targetClass) != null;
    }

    @FunctionalInterface
    private interface ThrowingSupplier<T> {
        T get() throws Exception;
    }

    /**
     * 支付宝客户端实例持有者内部容器
     */
    private record AlipayClientHolder(AlipayAppProperties properties,
                                      AlipayClient alipayClient,
                                      boolean certMode) {
    }
}
