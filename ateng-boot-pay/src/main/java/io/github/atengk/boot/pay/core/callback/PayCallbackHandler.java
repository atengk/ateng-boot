package io.github.atengk.boot.pay.core.callback;

import com.alipay.api.internal.util.AlipaySignature;
import com.wechat.pay.java.core.exception.DecryptionException;
import com.wechat.pay.java.core.exception.ValidationException;
import com.wechat.pay.java.core.notification.NotificationParser;
import com.wechat.pay.java.core.notification.RSANotificationConfig;
import com.wechat.pay.java.core.notification.RSAPublicKeyNotificationConfig;
import com.wechat.pay.java.core.notification.RequestParam;
import com.wechat.pay.java.service.payments.model.Transaction;
import com.wechat.pay.java.service.refund.model.RefundNotification;
import io.github.atengk.boot.pay.config.properties.AlipayAppProperties;
import io.github.atengk.boot.pay.config.properties.PayProperties;
import io.github.atengk.boot.pay.config.properties.WechatMerchantProperties;
import io.github.atengk.boot.pay.core.certificate.WechatAutoCertificateManager;
import io.github.atengk.boot.pay.core.lock.PayLockManager;
import io.github.atengk.boot.pay.core.merchant.NamedMerchantRegistry;
import io.github.atengk.boot.pay.enums.PayChannel;
import io.github.atengk.boot.pay.enums.PayResultCode;
import io.github.atengk.boot.pay.enums.PayTradeState;
import io.github.atengk.boot.pay.exception.PayException;
import io.github.atengk.boot.pay.exception.PaySignException;
import io.github.atengk.boot.pay.model.callback.PayCallbackResponse;
import io.github.atengk.boot.pay.model.callback.PayCallbackResult;
import io.github.atengk.boot.pay.model.callback.PaymentSuccessCallback;
import io.github.atengk.boot.pay.model.callback.RefundSuccessCallback;
import io.github.atengk.boot.pay.spi.PayCallbackListener;
import io.github.atengk.boot.pay.util.CurrencyUtil;
import io.github.atengk.boot.pay.util.PayKeyLoader;
import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.util.StreamUtils;

import java.io.IOException;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.cert.X509Certificate;
import java.time.Duration;
import java.util.*;
import java.util.function.Function;
import java.util.function.Supplier;

/**
 * 支付异步 Webhook 回调网关处理门面
 * <p>
 * 核心职责：
 * <ol>
 *     <li>自动解析微信支付 V3 请求标头（Signature, Timestamp, Nonce, Serial），利用平台公钥验签并利用 APIv3Key 解密 AES-GCM 载荷；</li>
 *     <li>自动解析支付宝 Form 参数，依据普通公钥或证书模式验证 RSA2 数字签名；</li>
 *     <li>在派发业务监听器前，自动基于 {@code channel:outTradeNo} 申请并发防重锁（默认 10 秒超时），阻断瞬时并发重放；</li>
 *     <li>分发至业务实现的 {@link PayCallbackListener} SPI 并根据 {@link PayCallbackResult} 转译为官方标准 ACK 报文；</li>
 *     <li>同时提供底层编程式解析与验签方法，满足业务自定义 Controller 灵活嵌入诉求。</li>
 * </ol>
 * </p>
 *
 * @author Ateng
 * @since 2026-10-23
 */
public class PayCallbackHandler {

    private static final Logger log = LoggerFactory.getLogger(PayCallbackHandler.class);

    private static final String JSON_CONTENT_TYPE = "application/json;charset=utf-8";
    private static final String TEXT_CONTENT_TYPE = "text/plain;charset=utf-8";

    private static final String WECHAT_HEADER_SERIAL = "Wechatpay-Serial";
    private static final String WECHAT_HEADER_SIGNATURE = "Wechatpay-Signature";
    private static final String WECHAT_HEADER_TIMESTAMP = "Wechatpay-Timestamp";
    private static final String WECHAT_HEADER_NONCE = "Wechatpay-Nonce";
    private static final String WECHAT_HEADER_SIGN_TYPE = "Wechatpay-Signature-Type";
    private static final String DEFAULT_SIGN_TYPE = "WECHATPAY2-SHA256-RSA2048";

    private final NamedMerchantRegistry namedMerchantRegistry;
    private final WechatAutoCertificateManager wechatAutoCertificateManager;
    private final PayLockManager payLockManager;
    private final PayProperties payProperties;
    private final List<PayCallbackListener> listeners;

    public PayCallbackHandler(NamedMerchantRegistry namedMerchantRegistry,
                              WechatAutoCertificateManager wechatAutoCertificateManager,
                              PayLockManager payLockManager,
                              PayProperties payProperties,
                              List<PayCallbackListener> listeners) {
        this.namedMerchantRegistry = Objects.requireNonNull(namedMerchantRegistry, "NamedMerchantRegistry 不能为空");
        this.wechatAutoCertificateManager = wechatAutoCertificateManager;
        this.payLockManager = Objects.requireNonNull(payLockManager, "PayLockManager 不能为空");
        this.payProperties = payProperties != null ? payProperties : new PayProperties();
        this.listeners = listeners != null ? listeners : Collections.emptyList();
    }

    // ==========================================
    // 微信支付 V3 回调处理
    // ==========================================

    /**
     * 完整处理微信支付 V3 Webhook 回调（自动智能识别支付成功 vs 退款通知事件，执行验签、解密、双轨防重锁、监听器派发与 ACK 应答转译）
     *
     * @param merchantKeyOrId 命名商户键或商户号
     * @param headers         HTTP 标头字典
     * @param body            原始请求体 JSON 字符串
     * @return 统一回调应答模型
     */
    public PayCallbackResponse handleWechat(String merchantKeyOrId, Map<String, String> headers, String body) {
        if (isWechatRefundNotification(body)) {
            return handleWechatRefund(merchantKeyOrId, headers, body);
        }
        return handleWechatPayment(merchantKeyOrId, headers, body);
    }

    /**
     * 完整处理微信支付 V3 退款通知 Webhook 回调（验签、解密、channel:outRefundNo 防重锁、监听器派发与 ACK 应答转译）
     *
     * @param merchantKeyOrId 命名商户键或商户号
     * @param headers         HTTP 标头字典
     * @param body            原始请求体 JSON 字符串
     * @return 统一回调应答模型
     */
    public PayCallbackResponse handleWechatRefund(String merchantKeyOrId, Map<String, String> headers, String body) {
        RefundSuccessCallback callback;
        try {
            callback = parseAndVerifyWechatRefund(merchantKeyOrId, headers, body);
        } catch (PaySignException e) {
            log.warn("WeChat refund callback signature verification or decryption failed for merchant [{}]: {}",
                    merchantKeyOrId, e.getMessage());
            return buildWechatResponse(HttpStatus.INTERNAL_SERVER_ERROR.value(), "FAIL",
                    "签名验证或解密失败: " + e.getMessage(), PayCallbackResult.fail(e.getMessage()), null, null);
        } catch (Exception e) {
            log.error("Unexpected error parsing WeChat refund callback for merchant [{}]: {}",
                    merchantKeyOrId, e.getMessage(), e);
            return buildWechatResponse(HttpStatus.INTERNAL_SERVER_ERROR.value(), "FAIL",
                    "回调报文解析异常: " + e.getMessage(), PayCallbackResult.fail(e.getMessage()), null, null);
        }

        String lockKey = PayChannel.WECHAT_PAY.name() + ":" + callback.getOutRefundNo();
        return processWithLock(
                lockKey,
                () -> dispatchRefund(callback),
                () -> {
                    log.warn("WeChat refund callback lock contention detected for key [{}], responding FAIL to trigger retry", lockKey);
                    return buildWechatResponse(HttpStatus.INTERNAL_SERVER_ERROR.value(), "FAIL",
                            "并发防重锁冲突，等待下一次重试", PayCallbackResult.fail("并发防重锁冲突"), null, callback);
                },
                result -> {
                    if (result.isFail()) {
                        log.warn("WeChat refund callback listener returned FAIL for outRefundNo [{}], reason: {}",
                                callback.getOutRefundNo(), result.getMessage());
                        return buildWechatResponse(HttpStatus.INTERNAL_SERVER_ERROR.value(), "FAIL",
                                result.getMessage(), result, null, callback);
                    }
                    return buildWechatResponse(HttpStatus.OK.value(), "SUCCESS", "成功", result, null, callback);
                }
        );
    }

    /**
     * 完整处理微信支付 V3 支付成功通知 Webhook 回调（验签、解密、channel:outTradeNo 防重锁、监听器派发与 ACK 应答转译）
     *
     * @param merchantKeyOrId 命名商户键或商户号
     * @param headers         HTTP 标头字典
     * @param body            原始请求体 JSON 字符串
     * @return 统一回调应答模型
     */
    public PayCallbackResponse handleWechatPayment(String merchantKeyOrId, Map<String, String> headers, String body) {
        PaymentSuccessCallback callback;
        try {
            callback = parseAndVerifyWechat(merchantKeyOrId, headers, body);
        } catch (PaySignException e) {
            log.warn("WeChat pay callback signature verification or decryption failed for merchant [{}]: {}",
                    merchantKeyOrId, e.getMessage());
            return buildWechatResponse(HttpStatus.INTERNAL_SERVER_ERROR.value(), "FAIL",
                    "签名验证或解密失败: " + e.getMessage(), PayCallbackResult.fail(e.getMessage()), null, null);
        } catch (Exception e) {
            log.error("Unexpected error parsing WeChat pay callback for merchant [{}]: {}",
                    merchantKeyOrId, e.getMessage(), e);
            return buildWechatResponse(HttpStatus.INTERNAL_SERVER_ERROR.value(), "FAIL",
                    "回调报文解析异常: " + e.getMessage(), PayCallbackResult.fail(e.getMessage()), null, null);
        }

        String lockKey = PayChannel.WECHAT_PAY.name() + ":" + callback.getOutTradeNo();
        return processWithLock(
                lockKey,
                () -> dispatch(callback),
                () -> {
                    log.warn("WeChat pay callback lock contention detected for key [{}], responding FAIL to trigger retry", lockKey);
                    return buildWechatResponse(HttpStatus.INTERNAL_SERVER_ERROR.value(), "FAIL",
                            "并发防重锁冲突，等待下一次重试", PayCallbackResult.fail("并发防重锁冲突"), callback, null);
                },
                result -> {
                    if (result.isFail()) {
                        log.warn("WeChat pay callback listener returned FAIL for outTradeNo [{}], reason: {}",
                                callback.getOutTradeNo(), result.getMessage());
                        return buildWechatResponse(HttpStatus.INTERNAL_SERVER_ERROR.value(), "FAIL",
                                result.getMessage(), result, callback, null);
                    }
                    return buildWechatResponse(HttpStatus.OK.value(), "SUCCESS", "成功", result, callback, null);
                }
        );
    }

    /**
     * 基于 HttpServletRequest 完整处理微信支付 V3 回调
     *
     * @param merchantKeyOrId 命名商户键或商户号
     * @param request         Servlet 请求
     * @return 统一回调应答模型
     */
    public PayCallbackResponse handleWechat(String merchantKeyOrId, HttpServletRequest request) {
        Objects.requireNonNull(request, "HttpServletRequest 不能为空");
        Map<String, String> headers = extractHeaders(request);
        String body;
        try {
            body = StreamUtils.copyToString(request.getInputStream(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            log.error("Failed to read WeChat pay callback request body: {}", e.getMessage(), e);
            return buildWechatResponse(HttpStatus.INTERNAL_SERVER_ERROR.value(), "FAIL",
                    "读取请求体失败", PayCallbackResult.fail("读取请求体失败"), null, null);
        }
        return handleWechat(merchantKeyOrId, headers, body);
    }

    /**
     * 编程式验签与解密微信支付 V3 支付成功回调报文
     *
     * @param merchantKeyOrId 命名商户键或商户号
     * @param headers         HTTP 标头字典
     * @param body            原始请求体 JSON 字符串
     * @return 标准化领域支付成功回调数据
     * @throws PaySignException 当签名验证失败、证书不存在或解密异常时抛出
     */
    public PaymentSuccessCallback parseAndVerifyWechat(String merchantKeyOrId, Map<String, String> headers, String body) {
        if (headers == null || headers.isEmpty()) {
            throw new PaySignException(PayResultCode.PAY_PARAM_INVALID, "微信支付回调 HTTP 标头不能为空");
        }
        if (body == null || body.isBlank()) {
            throw new PaySignException(PayResultCode.PAY_PARAM_INVALID, "微信支付回调请求体不能为空");
        }
        if (isWechatRefundNotification(body)) {
            throw new PayException(PayResultCode.PAY_PARAM_INVALID,
                    "当前通知为微信退款回调 (REFUND.*)，请调用 parseAndVerifyWechatRefund 方法解析");
        }

        WechatMerchantProperties merchant = namedMerchantRegistry.resolveWechatMerchant(merchantKeyOrId);
        String serialNumber = getHeaderCaseInsensitive(headers, WECHAT_HEADER_SERIAL);
        String signature = getHeaderCaseInsensitive(headers, WECHAT_HEADER_SIGNATURE);
        String timestamp = getHeaderCaseInsensitive(headers, WECHAT_HEADER_TIMESTAMP);
        String nonce = getHeaderCaseInsensitive(headers, WECHAT_HEADER_NONCE);
        String signType = getHeaderCaseInsensitive(headers, WECHAT_HEADER_SIGN_TYPE);
        if (signType == null || signType.isBlank()) {
            signType = DEFAULT_SIGN_TYPE;
        }

        if (serialNumber == null || signature == null || timestamp == null || nonce == null) {
            throw new PaySignException(PayResultCode.PAY_PARAM_INVALID,
                    "微信支付回调缺少必要签名标头 (Wechatpay-Serial, Wechatpay-Signature, Wechatpay-Timestamp, Wechatpay-Nonce)");
        }

        RequestParam requestParam = new RequestParam.Builder()
                .serialNumber(serialNumber)
                .nonce(nonce)
                .signature(signature)
                .timestamp(timestamp)
                .signType(signType)
                .body(body)
                .build();

        NotificationParser parser = createWechatNotificationParser(merchant, serialNumber);
        Transaction transaction;
        try {
            transaction = parser.parse(requestParam, Transaction.class);
        } catch (ValidationException e) {
            throw new PaySignException(PayResultCode.PAY_SIGN_VERIFY_ERROR, "微信支付回调签名验证失败: " + e.getMessage(), e);
        } catch (DecryptionException e) {
            throw new PaySignException(PayResultCode.PAY_SIGN_VERIFY_ERROR, "微信支付回调 AES-GCM 密文解密失败: " + e.getMessage(), e);
        } catch (Exception e) {
            throw new PaySignException(PayResultCode.PAY_SIGN_VERIFY_ERROR, "微信支付回调报文解析异常: " + e.getMessage(), e);
        }

        return mapWechatTransaction(merchantKeyOrId, transaction);
    }

    /**
     * 编程式验签与解密微信支付 V3 退款通知回调报文
     *
     * @param merchantKeyOrId 命名商户键或商户号
     * @param headers         HTTP 标头字典
     * @param body            原始请求体 JSON 字符串
     * @return 标准化领域退款成功回调数据
     * @throws PaySignException 当签名验证失败、证书不存在或解密异常时抛出
     */
    public RefundSuccessCallback parseAndVerifyWechatRefund(String merchantKeyOrId, Map<String, String> headers, String body) {
        if (headers == null || headers.isEmpty()) {
            throw new PaySignException(PayResultCode.PAY_PARAM_INVALID, "微信支付退款回调 HTTP 标头不能为空");
        }
        if (body == null || body.isBlank()) {
            throw new PaySignException(PayResultCode.PAY_PARAM_INVALID, "微信支付退款回调请求体不能为空");
        }

        WechatMerchantProperties merchant = namedMerchantRegistry.resolveWechatMerchant(merchantKeyOrId);
        String serialNumber = getHeaderCaseInsensitive(headers, WECHAT_HEADER_SERIAL);
        String signature = getHeaderCaseInsensitive(headers, WECHAT_HEADER_SIGNATURE);
        String timestamp = getHeaderCaseInsensitive(headers, WECHAT_HEADER_TIMESTAMP);
        String nonce = getHeaderCaseInsensitive(headers, WECHAT_HEADER_NONCE);
        String signType = getHeaderCaseInsensitive(headers, WECHAT_HEADER_SIGN_TYPE);
        if (signType == null || signType.isBlank()) {
            signType = DEFAULT_SIGN_TYPE;
        }

        if (serialNumber == null || signature == null || timestamp == null || nonce == null) {
            throw new PaySignException(PayResultCode.PAY_PARAM_INVALID,
                    "微信支付退款回调缺少必要签名标头 (Wechatpay-Serial, Wechatpay-Signature, Wechatpay-Timestamp, Wechatpay-Nonce)");
        }

        RequestParam requestParam = new RequestParam.Builder()
                .serialNumber(serialNumber)
                .nonce(nonce)
                .signature(signature)
                .timestamp(timestamp)
                .signType(signType)
                .body(body)
                .build();

        NotificationParser parser = createWechatNotificationParser(merchant, serialNumber);
        RefundNotification refundNotification;
        try {
            refundNotification = parser.parse(requestParam, RefundNotification.class);
        } catch (ValidationException e) {
            throw new PaySignException(PayResultCode.PAY_SIGN_VERIFY_ERROR, "微信支付退款回调签名验证失败: " + e.getMessage(), e);
        } catch (DecryptionException e) {
            throw new PaySignException(PayResultCode.PAY_SIGN_VERIFY_ERROR, "微信支付退款回调 AES-GCM 密文解密失败: " + e.getMessage(), e);
        } catch (Exception e) {
            throw new PaySignException(PayResultCode.PAY_SIGN_VERIFY_ERROR, "微信支付退款回调报文解析异常: " + e.getMessage(), e);
        }

        return mapWechatRefundNotification(merchantKeyOrId, refundNotification);
    }

    /**
     * 判断当前微信通知报文是否属于退款事件 (REFUND.SUCCESS, REFUND.ABNORMAL, REFUND.CLOSED)
     *
     * @param body 回调 JSON 原始报文
     * @return true 表示为退款通知
     */
    public static boolean isWechatRefundNotification(String body) {
        if (body == null || body.isBlank()) {
            return false;
        }
        return body.contains("\"REFUND.SUCCESS\"")
                || body.contains("\"REFUND.ABNORMAL\"")
                || body.contains("\"REFUND.CLOSED\"")
                || (body.contains("\"event_type\"") && body.contains("REFUND."));
    }

    /**
     * 构建微信通知解析器（优先静态平台公钥，回退证书自动管理器与配置证书）
     */
    private NotificationParser createWechatNotificationParser(WechatMerchantProperties merchant, String serialNumber) {
        // 1. 若配置了微信平台公钥（静态公钥模式）
        if (merchant.getWechatPayPublicKey() != null && !merchant.getWechatPayPublicKey().isBlank()) {
            String publicKeyId = merchant.getWechatPayPublicKeyId() != null && !merchant.getWechatPayPublicKeyId().isBlank()
                    ? merchant.getWechatPayPublicKeyId()
                    : serialNumber;
            RSAPublicKeyNotificationConfig config = new RSAPublicKeyNotificationConfig.Builder()
                    .publicKey(PayKeyLoader.cleanCertificatePem(merchant.getWechatPayPublicKey()))
                    .publicKeyId(publicKeyId)
                    .apiV3Key(merchant.getApiV3Key())
                    .build();
            return new NotificationParser(config);
        }

        // 2. 平台证书模式
        X509Certificate cert = null;
        if (wechatAutoCertificateManager != null) {
            try {
                cert = wechatAutoCertificateManager.getCertificate(merchant.getMchId(), serialNumber);
            } catch (Exception e) {
                log.debug("WechatAutoCertificateManager certificate lookup skipped for serialNumber [{}]: {}", serialNumber, e.getMessage());
            }
        }
        if (cert == null && merchant.getPrivateKeyPath() != null) {
            // 尝试通过自动证书管理器获取有效证书
            if (wechatAutoCertificateManager != null) {
                try {
                    cert = wechatAutoCertificateManager.getAvailableCertificate(merchant.getMchId());
                } catch (Exception e) {
                    log.debug("WechatAutoCertificateManager fallback lookup skipped: {}", e.getMessage());
                }
            }
        }

        if (cert == null) {
            throw new PaySignException(PayResultCode.PAY_SIGN_VERIFY_ERROR,
                    "未找到序列号为 [" + serialNumber + "] 的微信支付平台证书 (merchantId=" + merchant.getMchId() + ")");
        }

        RSANotificationConfig config = new RSANotificationConfig.Builder()
                .certificates(cert)
                .apiV3Key(merchant.getApiV3Key())
                .build();
        return new NotificationParser(config);
    }

    private PaymentSuccessCallback mapWechatTransaction(String merchantKey, Transaction tx) {
        BigDecimal amount = BigDecimal.ZERO;
        if (tx.getAmount() != null && tx.getAmount().getTotal() != null) {
            amount = CurrencyUtil.fenToYuan(tx.getAmount().getTotal());
        }

        String payerId = tx.getPayer() != null ? tx.getPayer().getOpenid() : null;
        PayTradeState tradeState = mapWechatTradeState(tx.getTradeState());

        return PaymentSuccessCallback.builder()
                .channel(PayChannel.WECHAT_PAY)
                .merchantKey(merchantKey)
                .outTradeNo(tx.getOutTradeNo())
                .transactionId(tx.getTransactionId())
                .amount(amount)
                .payerId(payerId)
                .payTime(tx.getSuccessTime())
                .tradeState(tradeState)
                .attach(tx.getAttach())
                .rawPayload(tx)
                .build();
    }

    private RefundSuccessCallback mapWechatRefundNotification(String merchantKey, RefundNotification rn) {
        BigDecimal refundAmount = BigDecimal.ZERO;
        BigDecimal totalAmount = BigDecimal.ZERO;
        if (rn.getAmount() != null) {
            if (rn.getAmount().getRefund() != null) {
                refundAmount = CurrencyUtil.fenToYuan(rn.getAmount().getRefund());
            }
            if (rn.getAmount().getTotal() != null) {
                totalAmount = CurrencyUtil.fenToYuan(rn.getAmount().getTotal());
            }
        }

        String refundTime = rn.getSuccessTime();
        if (refundTime == null || refundTime.isBlank()) {
            refundTime = rn.getCreateTime();
        }

        String refundStatus = rn.getRefundStatus() != null ? rn.getRefundStatus().name() : "SUCCESS";

        return RefundSuccessCallback.builder()
                .channel(PayChannel.WECHAT_PAY)
                .merchantKey(merchantKey)
                .outTradeNo(rn.getOutTradeNo())
                .outRefundNo(rn.getOutRefundNo())
                .transactionId(rn.getTransactionId())
                .refundId(rn.getRefundId())
                .refundAmount(refundAmount)
                .totalAmount(totalAmount)
                .refundTime(refundTime)
                .refundStatus(refundStatus)
                .rawPayload(rn)
                .build();
    }

    private PayTradeState mapWechatTradeState(Transaction.TradeStateEnum state) {
        if (state == null) {
            return PayTradeState.PAYERROR;
        }
        return switch (state) {
            case SUCCESS -> PayTradeState.SUCCESS;
            case NOTPAY -> PayTradeState.NOTPAY;
            case USERPAYING -> PayTradeState.USERPAYING;
            case CLOSED -> PayTradeState.CLOSED;
            case REVOKED -> PayTradeState.REVOKED;
            case REFUND -> PayTradeState.REFUND;
            case PAYERROR -> PayTradeState.PAYERROR;
            default -> PayTradeState.PAYERROR;
        };
    }

    // ==========================================
    // 支付宝回调处理
    // ==========================================

    /**
     * 完整处理支付宝 Webhook 回调（自动智能识别支付成功 vs 退款事件，执行验签、双轨防重锁、监听器派发与 ACK 应答转译）
     *
     * @param appKeyOrId 命名应用键或 AppId
     * @param params     支付宝回调表单参数字典
     * @return 统一回调应答模型
     */
    public PayCallbackResponse handleAlipay(String appKeyOrId, Map<String, String> params) {
        if (isAlipayRefundNotification(params)) {
            return handleAlipayRefund(appKeyOrId, params);
        }
        return handleAlipayPayment(appKeyOrId, params);
    }

    /**
     * 完整处理支付宝退款通知 Webhook 回调（验签、channel:outRefundNo 防重锁、监听器派发与 ACK 应答转译）
     *
     * @param appKeyOrId 命名应用键或 AppId
     * @param params     支付宝回调表单参数字典
     * @return 统一回调应答模型
     */
    public PayCallbackResponse handleAlipayRefund(String appKeyOrId, Map<String, String> params) {
        RefundSuccessCallback callback;
        try {
            callback = parseAndVerifyAlipayRefund(appKeyOrId, params);
        } catch (PaySignException e) {
            log.warn("Alipay refund callback signature verification failed for app [{}]: {}", appKeyOrId, e.getMessage());
            return buildAlipayResponse("fail", PayCallbackResult.fail(e.getMessage()), null, null);
        } catch (Exception e) {
            log.error("Unexpected error parsing Alipay refund callback for app [{}]: {}", appKeyOrId, e.getMessage(), e);
            return buildAlipayResponse("fail", PayCallbackResult.fail(e.getMessage()), null, null);
        }

        String lockKey = PayChannel.ALIPAY.name() + ":" + callback.getOutRefundNo();
        return processWithLock(
                lockKey,
                () -> dispatchRefund(callback),
                () -> {
                    log.warn("Alipay refund callback lock contention detected for key [{}], responding fail to trigger retry", lockKey);
                    return buildAlipayResponse("fail", PayCallbackResult.fail("并发防重锁冲突"), null, callback);
                },
                result -> {
                    if (result.isFail()) {
                        log.warn("Alipay refund callback listener returned FAIL for outRefundNo [{}], reason: {}",
                                callback.getOutRefundNo(), result.getMessage());
                        return buildAlipayResponse("fail", result, null, callback);
                    }
                    return buildAlipayResponse("success", result, null, callback);
                }
        );
    }

    /**
     * 完整处理支付宝支付成功 Webhook 回调（验签、channel:outTradeNo 防重锁、监听器派发与 ACK 应答转译）
     *
     * @param appKeyOrId 命名应用键或 AppId
     * @param params     支付宝回调表单参数字典
     * @return 统一回调应答模型
     */
    public PayCallbackResponse handleAlipayPayment(String appKeyOrId, Map<String, String> params) {
        PaymentSuccessCallback callback;
        try {
            callback = parseAndVerifyAlipay(appKeyOrId, params);
        } catch (PaySignException e) {
            log.warn("Alipay callback signature verification failed for app [{}]: {}", appKeyOrId, e.getMessage());
            return buildAlipayResponse("fail", PayCallbackResult.fail(e.getMessage()), null, null);
        } catch (Exception e) {
            log.error("Unexpected error parsing Alipay callback for app [{}]: {}", appKeyOrId, e.getMessage(), e);
            return buildAlipayResponse("fail", PayCallbackResult.fail(e.getMessage()), null, null);
        }

        String lockKey = PayChannel.ALIPAY.name() + ":" + callback.getOutTradeNo();
        return processWithLock(
                lockKey,
                () -> dispatch(callback),
                () -> {
                    log.warn("Alipay callback lock contention detected for key [{}], responding fail to trigger retry", lockKey);
                    return buildAlipayResponse("fail", PayCallbackResult.fail("并发防重锁冲突"), callback, null);
                },
                result -> {
                    if (result.isFail()) {
                        log.warn("Alipay callback listener returned FAIL for outTradeNo [{}], reason: {}",
                                callback.getOutTradeNo(), result.getMessage());
                        return buildAlipayResponse("fail", result, callback, null);
                    }
                    return buildAlipayResponse("success", result, callback, null);
                }
        );
    }

    /**
     * 基于 HttpServletRequest 完整处理支付宝回调
     *
     * @param appKeyOrId 命名应用键或 AppId
     * @param request    Servlet 请求
     * @return 统一回调应答模型
     */
    public PayCallbackResponse handleAlipay(String appKeyOrId, HttpServletRequest request) {
        Objects.requireNonNull(request, "HttpServletRequest 不能为空");
        Map<String, String> params = extractParameters(request);
        return handleAlipay(appKeyOrId, params);
    }

    /**
     * 编程式验签与解析支付宝支付成功回调参数
     *
     * @param appKeyOrId 命名应用键或 AppId
     * @param params     表单参数字典
     * @return 标准化领域支付成功回调数据
     * @throws PaySignException 当验签失败或参数缺失时抛出
     */
    public PaymentSuccessCallback parseAndVerifyAlipay(String appKeyOrId, Map<String, String> params) {
        verifyAlipaySignature(appKeyOrId, params);
        return mapAlipayParams(appKeyOrId, params);
    }

    /**
     * 编程式验签与解析支付宝退款回调参数
     *
     * @param appKeyOrId 命名应用键或 AppId
     * @param params     表单参数字典
     * @return 标准化领域退款成功回调数据
     * @throws PaySignException 当验签失败或参数缺失时抛出
     */
    public RefundSuccessCallback parseAndVerifyAlipayRefund(String appKeyOrId, Map<String, String> params) {
        verifyAlipaySignature(appKeyOrId, params);
        return mapAlipayRefundParams(appKeyOrId, params);
    }

    /**
     * 判断当前支付宝回调参数是否属于退款通知 (包含 refund_fee 且大于 0)
     *
     * @param params 支付宝回调表单参数字典
     * @return true 表示为退款通知
     */
    public static boolean isAlipayRefundNotification(Map<String, String> params) {
        if (params == null || params.isEmpty()) {
            return false;
        }
        String refundFee = params.get("refund_fee");
        return refundFee != null && !refundFee.isBlank() && !refundFee.equals("0.00") && !refundFee.equals("0");
    }

    private void verifyAlipaySignature(String appKeyOrId, Map<String, String> params) {
        if (params == null || params.isEmpty()) {
            throw new PaySignException(PayResultCode.PAY_PARAM_INVALID, "支付宝回调参数列表不能为空");
        }

        AlipayAppProperties app = namedMerchantRegistry.resolveAlipayApp(appKeyOrId);
        String charset = app.getCharset() != null && !app.getCharset().isBlank() ? app.getCharset() : "UTF-8";
        String signType = app.getSignType() != null && !app.getSignType().isBlank() ? app.getSignType() : "RSA2";

        // 防御外部不可变 Map 以及 AlipaySignature.rsaCheckV1 内部破坏性移除 sign/sign_type
        Map<String, String> verifyParams = new HashMap<>(params);

        boolean verified = false;
        try {
            if (app.isCertMode()) {
                String certPath = app.getAlipayCertPath();
                if (certPath == null || certPath.isBlank()) {
                    throw new PaySignException(PayResultCode.PAY_PARAM_INVALID, "支付宝公钥证书路径未配置 (alipayCertPath)");
                }
                X509Certificate cert = PayKeyLoader.loadCertificate(certPath);
                String publicKey = Base64.getEncoder().encodeToString(cert.getPublicKey().getEncoded());
                verified = AlipaySignature.rsaCheckV1(verifyParams, publicKey, charset, signType);
            } else {
                String publicKey = app.getAlipayPublicKey();
                if (publicKey == null || publicKey.isBlank()) {
                    throw new PaySignException(PayResultCode.PAY_PARAM_INVALID, "支付宝公钥未配置 (alipayPublicKey)");
                }
                String cleanKey = PayKeyLoader.cleanCertificatePem(publicKey);
                verified = AlipaySignature.rsaCheckV1(verifyParams, cleanKey, charset, signType);
            }
        } catch (PaySignException e) {
            throw e;
        } catch (Exception e) {
            throw new PaySignException(PayResultCode.PAY_SIGN_VERIFY_ERROR, "支付宝回调签名验证异常: " + e.getMessage(), e);
        }

        if (!verified) {
            throw new PaySignException(PayResultCode.PAY_SIGN_VERIFY_ERROR, "支付宝回调签名验证失败，可能被篡改");
        }
    }

    private PaymentSuccessCallback mapAlipayParams(String appKey, Map<String, String> params) {
        String outTradeNo = params.get("out_trade_no");
        String tradeNo = params.get("trade_no");
        String totalAmountStr = params.get("total_amount");
        BigDecimal amount = BigDecimal.ZERO;
        if (totalAmountStr != null && !totalAmountStr.isBlank()) {
            try {
                amount = new BigDecimal(totalAmountStr);
            } catch (Exception e) {
                throw new PaySignException(PayResultCode.PAY_PARAM_INVALID,
                        "支付宝回调金额解析失败: [total_amount=" + totalAmountStr + "]", e);
            }
        }

        String buyerId = params.get("buyer_id");
        if (buyerId == null || buyerId.isBlank()) {
            buyerId = params.get("buyer_open_id");
        }

        String payTime = params.get("gmt_payment");
        if (payTime == null || payTime.isBlank()) {
            payTime = params.get("notify_time");
        }

        String tradeStatus = params.get("trade_status");
        PayTradeState tradeState = mapAlipayTradeState(tradeStatus);
        String attach = params.get("passback_params");

        return PaymentSuccessCallback.builder()
                .channel(PayChannel.ALIPAY)
                .merchantKey(appKey)
                .outTradeNo(outTradeNo)
                .transactionId(tradeNo)
                .amount(amount)
                .payerId(buyerId)
                .payTime(payTime)
                .tradeState(tradeState)
                .attach(attach)
                .rawPayload(Collections.unmodifiableMap(params))
                .build();
    }

    private RefundSuccessCallback mapAlipayRefundParams(String appKey, Map<String, String> params) {
        String outTradeNo = params.get("out_trade_no");
        String tradeNo = params.get("trade_no");
        String outRefundNo = params.get("out_biz_no");
        if (outRefundNo == null || outRefundNo.isBlank()) {
            outRefundNo = params.get("out_request_no");
        }
        if (outRefundNo == null || outRefundNo.isBlank()) {
            outRefundNo = outTradeNo;
        }

        String refundFeeStr = params.get("refund_fee");
        BigDecimal refundAmount = BigDecimal.ZERO;
        if (refundFeeStr != null && !refundFeeStr.isBlank()) {
            try {
                refundAmount = new BigDecimal(refundFeeStr);
            } catch (Exception e) {
                throw new PaySignException(PayResultCode.PAY_PARAM_INVALID,
                        "支付宝退款回调金额解析失败: [refund_fee=" + refundFeeStr + "]", e);
            }
        }

        String totalAmountStr = params.get("total_amount");
        BigDecimal totalAmount = null;
        if (totalAmountStr != null && !totalAmountStr.isBlank()) {
            try {
                totalAmount = new BigDecimal(totalAmountStr);
            } catch (Exception e) {
                throw new PaySignException(PayResultCode.PAY_PARAM_INVALID,
                        "支付宝退款回调总金额解析失败: [total_amount=" + totalAmountStr + "]", e);
            }
        }

        String refundTime = params.get("gmt_refund");
        if (refundTime == null || refundTime.isBlank()) {
            refundTime = params.get("notify_time");
        }

        return RefundSuccessCallback.builder()
                .channel(PayChannel.ALIPAY)
                .merchantKey(appKey)
                .outTradeNo(outTradeNo)
                .outRefundNo(outRefundNo)
                .transactionId(tradeNo)
                .refundId(tradeNo)
                .refundAmount(refundAmount)
                .totalAmount(totalAmount)
                .refundTime(refundTime)
                .refundStatus("REFUND_SUCCESS")
                .rawPayload(Collections.unmodifiableMap(params))
                .build();
    }

    private PayTradeState mapAlipayTradeState(String tradeStatus) {
        if (tradeStatus == null) {
            return PayTradeState.PAYERROR;
        }
        return switch (tradeStatus) {
            case "TRADE_SUCCESS", "TRADE_FINISHED" -> PayTradeState.SUCCESS;
            case "WAIT_BUYER_PAY" -> PayTradeState.NOTPAY;
            case "TRADE_CLOSED" -> PayTradeState.CLOSED;
            default -> PayTradeState.PAYERROR;
        };
    }

    // ==========================================
    // 监听器分发逻辑
    // ==========================================

    private PayCallbackResult dispatch(PaymentSuccessCallback callback) {
        if (listeners.isEmpty()) {
            log.info("No PayCallbackListener registered in application context, auto ACK callback for [{}]",
                    callback.getOutTradeNo());
            return PayCallbackResult.ignore("未注册任何回调监听器");
        }

        PayCallbackResult lastResult = PayCallbackResult.success();
        for (PayCallbackListener listener : listeners) {
            if (listener.supports(callback)) {
                try {
                    PayCallbackResult res = listener.onPaymentSuccess(callback);
                    if (res != null) {
                        if (res.isFail()) {
                            return res;
                        }
                        lastResult = res;
                    }
                } catch (Exception e) {
                    log.error("PayCallbackListener [{}] threw exception processing callback for [{}]: {}",
                            listener.getClass().getName(), callback.getOutTradeNo(), e.getMessage(), e);
                    return PayCallbackResult.fail("监听器执行异常: " + e.getMessage());
                }
            }
        }
        return lastResult;
    }

    /**
     * 派发退款成功异步回调事件给业务监听器
     *
     * @param callback 标准化退款成功回调事件
     * @return 业务处理结果
     */
    public PayCallbackResult dispatchRefund(RefundSuccessCallback callback) {
        if (listeners.isEmpty()) {
            log.info("No PayCallbackListener registered in application context, auto ACK refund callback for [{}]",
                    callback.getOutRefundNo());
            return PayCallbackResult.ignore("未注册任何回调监听器");
        }

        PayCallbackResult lastResult = PayCallbackResult.success();
        for (PayCallbackListener listener : listeners) {
            if (listener.supports(callback)) {
                try {
                    PayCallbackResult res = listener.onRefundSuccess(callback);
                    if (res != null) {
                        if (res.isFail()) {
                            return res;
                        }
                        lastResult = res;
                    }
                } catch (Exception e) {
                    log.error("PayCallbackListener [{}] threw exception processing refund for [{}]: {}",
                            listener.getClass().getName(), callback.getOutRefundNo(), e.getMessage(), e);
                    return PayCallbackResult.fail("退款监听器执行异常: " + e.getMessage());
                }
            }
        }
        return lastResult;
    }

    /**
     * 通用受管防重并发锁执行模板
     */
    private PayCallbackResponse processWithLock(
            String lockKey,
            Supplier<PayCallbackResult> dispatchAction,
            Supplier<PayCallbackResponse> conflictResponseSupplier,
            Function<PayCallbackResult, PayCallbackResponse> resultResponseFunction) {

        Duration timeout = payProperties.getCallback() != null && payProperties.getCallback().getIdempotentTimeout() != null
                ? payProperties.getCallback().getIdempotentTimeout()
                : Duration.ofSeconds(10);

        boolean locked = payLockManager.tryLock(lockKey, timeout);
        if (!locked) {
            return conflictResponseSupplier.get();
        }

        try {
            PayCallbackResult result = dispatchAction.get();
            return resultResponseFunction.apply(result);
        } finally {
            payLockManager.unlock(lockKey);
        }
    }

    // ==========================================
    // 响应构造辅助方法
    // ==========================================

    private PayCallbackResponse buildWechatResponse(int httpStatus, String code, String message,
                                                    PayCallbackResult result,
                                                    PaymentSuccessCallback callback,
                                                    RefundSuccessCallback refundCallback) {
        String json = String.format("{\"code\":\"%s\",\"message\":\"%s\"}", code, escapeJson(message));
        return PayCallbackResponse.builder()
                .httpStatus(httpStatus)
                .contentType(JSON_CONTENT_TYPE)
                .responseBody(json)
                .result(result != null ? result : (httpStatus == 200 ? PayCallbackResult.success() : PayCallbackResult.fail(message)))
                .callback(callback)
                .refundCallback(refundCallback)
                .build();
    }

    private PayCallbackResponse buildWechatResponse(int httpStatus, String code, String message,
                                                    PayCallbackResult result, PaymentSuccessCallback callback) {
        return buildWechatResponse(httpStatus, code, message, result, callback, null);
    }

    private PayCallbackResponse buildAlipayResponse(String responseText, PayCallbackResult result,
                                                    PaymentSuccessCallback callback,
                                                    RefundSuccessCallback refundCallback) {
        return PayCallbackResponse.builder()
                .httpStatus(HttpStatus.OK.value())
                .contentType(TEXT_CONTENT_TYPE)
                .responseBody(responseText)
                .result(result != null ? result : ("success".equals(responseText) ? PayCallbackResult.success() : PayCallbackResult.fail("fail")))
                .callback(callback)
                .refundCallback(refundCallback)
                .build();
    }

    private PayCallbackResponse buildAlipayResponse(String responseText, PayCallbackResult result, PaymentSuccessCallback callback) {
        return buildAlipayResponse(responseText, result, callback, null);
    }

    private String escapeJson(String input) {
        if (input == null) {
            return "";
        }
        return input.replace("\"", "\\\"").replace("\n", "").replace("\r", "");
    }

    private String getHeaderCaseInsensitive(Map<String, String> headers, String headerName) {
        if (headers == null || headers.isEmpty() || headerName == null) {
            return null;
        }
        for (Map.Entry<String, String> entry : headers.entrySet()) {
            if (headerName.equalsIgnoreCase(entry.getKey())) {
                return entry.getValue();
            }
        }
        return null;
    }

    private Map<String, String> extractHeaders(HttpServletRequest request) {
        Map<String, String> map = new HashMap<>();
        Enumeration<String> headerNames = request.getHeaderNames();
        if (headerNames != null) {
            while (headerNames.hasMoreElements()) {
                String name = headerNames.nextElement();
                map.put(name, request.getHeader(name));
            }
        }
        return map;
    }

    private Map<String, String> extractParameters(HttpServletRequest request) {
        Map<String, String> map = new HashMap<>();
        Map<String, String[]> paramMap = request.getParameterMap();
        if (paramMap != null) {
            for (Map.Entry<String, String[]> entry : paramMap.entrySet()) {
                map.put(entry.getKey(), String.join(",", entry.getValue()));
            }
        }
        return map;
    }
}
