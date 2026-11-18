package io.github.atengk.boot.pay.core.callback;

import com.alipay.api.internal.util.AlipaySignature;
import com.wechat.pay.java.core.certificate.CertificateProvider;
import com.wechat.pay.java.core.cipher.AeadAesCipher;
import com.wechat.pay.java.core.exception.DecryptionException;
import com.wechat.pay.java.core.exception.ValidationException;
import com.wechat.pay.java.core.notification.AbstractNotificationConfig;
import com.wechat.pay.java.core.notification.NotificationParser;
import com.wechat.pay.java.core.notification.RSANotificationConfig;
import com.wechat.pay.java.core.notification.RSAPublicKeyNotificationConfig;
import com.wechat.pay.java.core.notification.RequestParam;
import com.wechat.pay.java.service.payments.model.Transaction;
import com.wechat.pay.java.service.refund.model.RefundNotification;
import io.github.atengk.boot.pay.config.properties.AlipayAppProperties;
import io.github.atengk.boot.pay.config.properties.PayHttpProperties;
import io.github.atengk.boot.pay.config.properties.PayProperties;
import io.github.atengk.boot.pay.config.properties.WechatMerchantProperties;
import io.github.atengk.boot.pay.core.WechatPayTemplate;
import io.github.atengk.boot.pay.core.certificate.WechatAutoCertificateManager;
import io.github.atengk.boot.pay.core.lock.PayLockManager;
import io.github.atengk.boot.pay.core.merchant.NamedMerchantRegistry;
import io.github.atengk.boot.pay.enums.PayChannel;
import io.github.atengk.boot.pay.enums.PayResultCode;
import io.github.atengk.boot.pay.enums.PayTradeState;
import io.github.atengk.boot.pay.enums.PayTransferState;
import io.github.atengk.boot.pay.exception.PayException;
import io.github.atengk.boot.pay.exception.PaySignException;
import io.github.atengk.boot.pay.model.callback.OrderClosedCallback;
import io.github.atengk.boot.pay.model.callback.PayCallbackResponse;
import io.github.atengk.boot.pay.model.callback.PayCallbackResult;
import io.github.atengk.boot.pay.model.callback.PaymentSuccessCallback;
import io.github.atengk.boot.pay.model.callback.RefundSuccessCallback;
import io.github.atengk.boot.pay.model.callback.TransferNotifyCallback;
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
import java.security.PublicKey;
import java.security.cert.X509Certificate;
import java.time.Duration;
import java.util.*;
import java.util.function.Function;
import java.util.function.Predicate;
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
     * 完整处理微信支付 V3 Webhook 回调（自动智能识别支付、退款与商家转账通知事件，执行验签、解密、双轨防重锁、监听器派发与 ACK 应答转译）
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
        if (isWechatTransferNotification(body)) {
            return handleWechatTransfer(merchantKeyOrId, headers, body);
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
     * 完整处理微信新版商家转账单据终态通知 Webhook 回调（{@code MCHTRANSFER.BILL.FINISHED}）
     *
     * @param merchantKeyOrId 命名商户键或商户号
     * @param headers         HTTP 标头字典
     * @param body            原始请求体 JSON 字符串
     * @return 统一回调应答模型
     */
    public PayCallbackResponse handleWechatTransfer(String merchantKeyOrId, Map<String, String> headers, String body) {
        TransferNotifyCallback callback;
        try {
            callback = parseAndVerifyWechatTransfer(merchantKeyOrId, headers, body);
        } catch (PaySignException e) {
            log.warn("WeChat transfer callback signature verification or decryption failed for merchant [{}]: {}",
                    merchantKeyOrId, e.getMessage());
            return buildWechatResponse(HttpStatus.INTERNAL_SERVER_ERROR.value(), "FAIL",
                    "签名验证或解密失败: " + e.getMessage(), PayCallbackResult.fail(e.getMessage()), null, null, null, null);
        } catch (Exception e) {
            log.error("Unexpected error parsing WeChat transfer callback for merchant [{}]: {}",
                    merchantKeyOrId, e.getMessage(), e);
            return buildWechatResponse(HttpStatus.INTERNAL_SERVER_ERROR.value(), "FAIL",
                    "回调报文解析异常: " + e.getMessage(), PayCallbackResult.fail(e.getMessage()), null, null, null, null);
        }

        String bizNo = firstNonBlankText(callback.getOutBizNo(), callback.getOrderId());
        if (!hasText(bizNo)) {
            return buildWechatResponse(HttpStatus.INTERNAL_SERVER_ERROR.value(), "FAIL",
                    "微信商家转账回调缺少转账单号", PayCallbackResult.fail("缺少转账单号"), null, null, null, callback);
        }
        return dispatchWechatWithLock(
                "transfer",
                PayChannel.WECHAT_PAY.name() + ":TRANSFER:" + bizNo,
                bizNo,
                () -> dispatchTransfer(callback),
                null, null, null, callback
        );
    }

    /**
     * 完整处理微信支付 V3 交易通知 Webhook 回调（严格终态守卫：仅 {@code SUCCESS} 触发支付成功监听，{@code CLOSED}/{@code REVOKED} 路由至 {@code onOrderClosed}，其余非成功态静默忽略）
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

        PayTradeState state = callback.getTradeState();
        if (state == PayTradeState.CLOSED || state == PayTradeState.REVOKED) {
            OrderClosedCallback closedCallback = toWechatOrderClosedCallback(merchantKeyOrId, callback);
            String outTradeNo = closedCallback.getOutTradeNo();
            if (!hasText(outTradeNo)) {
                return buildWechatResponse(HttpStatus.INTERNAL_SERVER_ERROR.value(), "FAIL",
                        "微信关单回调缺少商户订单号 [outTradeNo]", PayCallbackResult.fail("缺少商户订单号"), null, null, closedCallback, null);
            }
            return dispatchWechatWithLock(
                    "close",
                    PayChannel.WECHAT_PAY.name() + ":CLOSED:" + outTradeNo,
                    outTradeNo,
                    () -> dispatchOrderClosed(closedCallback),
                    null, null, closedCallback, null
            );
        }

        if (state != PayTradeState.SUCCESS) {
            log.info("Ignoring non-success WeChat payment notification: outTradeNo={}, tradeState={}",
                    callback.getOutTradeNo(), state);
            return buildWechatResponse(HttpStatus.OK.value(), "SUCCESS", "成功",
                    PayCallbackResult.ignore("忽略非支付成功状态通知: " + state), null, null, null, null);
        }

        String outTradeNo = callback.getOutTradeNo();
        if (!hasText(outTradeNo)) {
            return buildWechatResponse(HttpStatus.INTERNAL_SERVER_ERROR.value(), "FAIL",
                    "微信支付回调缺少商户订单号 [outTradeNo]", PayCallbackResult.fail("缺少商户订单号"), callback, null);
        }
        return dispatchWechatWithLock(
                "pay",
                PayChannel.WECHAT_PAY.name() + ":" + outTradeNo,
                outTradeNo,
                () -> dispatch(callback),
                callback, null, null, null
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
     * 编程式验签与解密微信支付 V3 支付回调报文
     *
     * @param merchantKeyOrId 命名商户键或商户号
     * @param headers         HTTP 标头字典
     * @param body            原始请求体 JSON 字符串
     * @return 标准化领域支付回调数据
     * @throws PaySignException 当签名验证失败、证书不存在或解密异常时抛出
     */
    public PaymentSuccessCallback parseAndVerifyWechat(String merchantKeyOrId, Map<String, String> headers, String body) {
        if (isWechatRefundNotification(body)) {
            throw new PayException(PayResultCode.PAY_PARAM_INVALID,
                    "当前通知为微信退款回调 (REFUND.*)，请调用 parseAndVerifyWechatRefund 方法解析");
        }
        if (isWechatTransferNotification(body)) {
            throw new PayException(PayResultCode.PAY_PARAM_INVALID,
                    "当前通知为微信商家转账回调 (MCHTRANSFER.*)，请调用 parseAndVerifyWechatTransfer 方法解析");
        }
        Transaction transaction = parseWechatNotification(merchantKeyOrId, headers, body, "微信支付回调", Transaction.class);
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
        RefundNotification refundNotification = parseWechatNotification(
                merchantKeyOrId, headers, body, "微信支付退款回调", RefundNotification.class);
        return mapWechatRefundNotification(merchantKeyOrId, refundNotification);
    }

    /**
     * 编程式验签与解密微信新版商家转账单据终态通知回调报文 ({@code MCHTRANSFER.BILL.FINISHED})
     *
     * @param merchantKeyOrId 命名商户键或商户号
     * @param headers         HTTP 标头字典
     * @param body            原始请求体 JSON 字符串
     * @return 标准化领域商家转账通知回调数据
     * @throws PaySignException 当签名验证失败、证书不存在或解密异常时抛出
     */
    public TransferNotifyCallback parseAndVerifyWechatTransfer(String merchantKeyOrId, Map<String, String> headers, String body) {
        WechatTransferBillNotification notification = parseWechatNotification(
                merchantKeyOrId, headers, body, "微信商家转账回调", WechatTransferBillNotification.class);
        return mapWechatTransferNotification(merchantKeyOrId, notification);
    }

    private <T> T parseWechatNotification(String merchantKeyOrId,
                                          Map<String, String> headers,
                                          String body,
                                          String sceneLabel,
                                          Class<T> targetClass) {
        if (headers == null || headers.isEmpty()) {
            throw new PaySignException(PayResultCode.PAY_PARAM_INVALID, sceneLabel + " HTTP 标头不能为空");
        }
        if (body == null || body.isBlank()) {
            throw new PaySignException(PayResultCode.PAY_PARAM_INVALID, sceneLabel + "请求体不能为空");
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
                    sceneLabel + "缺少必要签名标头 (Wechatpay-Serial, Wechatpay-Signature, Wechatpay-Timestamp, Wechatpay-Nonce)");
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
        try {
            return parser.parse(requestParam, targetClass);
        } catch (ValidationException e) {
            throw new PaySignException(PayResultCode.PAY_SIGN_VERIFY_ERROR, sceneLabel + "签名验证失败: " + e.getMessage(), e);
        } catch (DecryptionException e) {
            throw new PaySignException(PayResultCode.PAY_SIGN_VERIFY_ERROR, sceneLabel + " AES-GCM 密文解密失败: " + e.getMessage(), e);
        } catch (Exception e) {
            throw new PaySignException(PayResultCode.PAY_SIGN_VERIFY_ERROR, sceneLabel + "报文解析异常: " + e.getMessage(), e);
        }
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
     * 判断当前微信通知报文是否属于新版商家转账事件 (MCHTRANSFER.BILL.FINISHED)
     *
     * @param body 回调 JSON 原始报文
     * @return true 表示为商家转账通知
     */
    public static boolean isWechatTransferNotification(String body) {
        if (body == null || body.isBlank()) {
            return false;
        }
        return body.contains("\"MCHTRANSFER.BILL.FINISHED\"")
                || (body.contains("\"event_type\"") && body.contains("MCHTRANSFER."));
    }

    /**
     * 构建微信通知解析器（优先静态平台公钥，回退证书自动管理器与配置证书）
     */
    private NotificationParser createWechatNotificationParser(WechatMerchantProperties merchant, String serialNumber) {
        PayHttpProperties httpProps = PayHttpProperties.orDefault(
                payProperties.getWechat() != null ? payProperties.getWechat().getHttp() : null);

        // 1. 若配置了微信平台公钥（支持灰度双轨模式：publicKey + autoCertificate=true）
        if (merchant.getApiV3Key() == null || merchant.getApiV3Key().isBlank()) {
            throw new PayException(PayResultCode.PAY_PARAM_INVALID, "微信支付 APIv3 密钥 [apiV3Key] 不能为空");
        }
        if (merchant.hasPublicKey()) {
            String pubKeySource = merchant.resolvePublicKeySource();
            PublicKey pubKey = PayKeyLoader.loadPublicKey(pubKeySource);
            String publicKeyId = merchant.requirePublicKeyId();
            if (merchant.isAutoCertificate() && wechatAutoCertificateManager != null) {
                wechatAutoCertificateManager.registerMerchantIfReady(merchant, httpProps, null);
                CertificateProvider certProvider =
                        wechatAutoCertificateManager.buildCertificateProvider(merchant.getMchId());
                AeadAesCipher aeadCipher =
                        new AeadAesCipher(merchant.getApiV3Key().getBytes(StandardCharsets.UTF_8));
                AbstractNotificationConfig grayscaleConfig = new AbstractNotificationConfig(
                        "WECHATPAY2-SHA256-RSA2048",
                        "AEAD_AES_256_GCM",
                        certProvider,
                        pubKey,
                        publicKeyId,
                        aeadCipher) {};
                return new NotificationParser(grayscaleConfig);
            }
            RSAPublicKeyNotificationConfig config = new RSAPublicKeyNotificationConfig.Builder()
                    .publicKey(pubKey)
                    .publicKeyId(publicKeyId)
                    .apiV3Key(merchant.getApiV3Key())
                    .build();
            return new NotificationParser(config);
        }

        // 2. 平台证书模式
        if (wechatAutoCertificateManager != null) {
            wechatAutoCertificateManager.registerMerchantIfReady(merchant, httpProps, null);
        }
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

    private TransferNotifyCallback mapWechatTransferNotification(String merchantKey, WechatTransferBillNotification notif) {
        BigDecimal amount = notif.getTransferAmount() != null
                ? CurrencyUtil.fenToYuan(notif.getTransferAmount())
                : BigDecimal.ZERO;
        PayTransferState status = WechatPayTemplate.mapWechatTransferBillStatus(notif.getState());
        String payTime = firstNonBlankText(notif.getUpdateTime(), notif.getCreateTime());

        return TransferNotifyCallback.builder()
                .channel(PayChannel.WECHAT_PAY)
                .merchantKey(merchantKey)
                .outBizNo(notif.getOutBillNo())
                .orderId(notif.getTransferBillNo())
                .status(status)
                .rawStatus(notif.getState())
                .amount(amount)
                .payeeAccount(notif.getOpenid())
                .payTime(payTime)
                .failReason(notif.getFailReason())
                .rawPayload(notif)
                .build();
    }

    private OrderClosedCallback toWechatOrderClosedCallback(String merchantKey, PaymentSuccessCallback payment) {
        String rawState = null;
        String closeReason = null;
        String closeTime = payment.getPayTime();
        if (payment.getRawPayload() instanceof Transaction tx) {
            rawState = tx.getTradeState() != null ? tx.getTradeState().name() : null;
            closeReason = tx.getTradeStateDesc();
        }
        return OrderClosedCallback.builder()
                .channel(PayChannel.WECHAT_PAY)
                .merchantKey(merchantKey)
                .outTradeNo(payment.getOutTradeNo())
                .transactionId(payment.getTransactionId())
                .amount(payment.getAmount())
                .closeTime(closeTime)
                .tradeState(payment.getTradeState())
                .rawTradeState(firstNonBlankText(rawState, payment.getTradeState().getCode()))
                .closeReason(closeReason)
                .attach(payment.getAttach())
                .rawPayload(payment.getRawPayload())
                .build();
    }

    private OrderClosedCallback toAlipayOrderClosedCallback(String appKey,
                                                            Map<String, String> params,
                                                            PaymentSuccessCallback payment) {
        String closeTime = firstNonBlankText(params.get("gmt_close"), params.get("notify_time"));
        String tradeStatus = params.get("trade_status");
        return OrderClosedCallback.builder()
                .channel(PayChannel.ALIPAY)
                .merchantKey(appKey)
                .outTradeNo(payment.getOutTradeNo())
                .transactionId(payment.getTransactionId())
                .amount(payment.getAmount())
                .closeTime(closeTime)
                .tradeState(payment.getTradeState())
                .rawTradeState(firstNonBlankText(tradeStatus, payment.getTradeState().getCode()))
                .closeReason(tradeStatus)
                .attach(payment.getAttach())
                .rawPayload(payment.getRawPayload())
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
     * 完整处理支付宝 Webhook 回调（自动智能识别支付、退款与关单事件，执行验签、双轨防重锁、监听器派发与 ACK 应答转译）
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

        if (!"REFUND_SUCCESS".equalsIgnoreCase(callback.getRefundStatus())) {
            log.info("Ignoring non-success Alipay refund notification: outRefundNo={}, refundStatus={}",
                    callback.getOutRefundNo(), callback.getRefundStatus());
            return buildAlipayResponse("success",
                    PayCallbackResult.ignore("忽略非退款成功状态通知: " + callback.getRefundStatus()),
                    null, callback, null, null);
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
     * 完整处理支付宝交易通知 Webhook 回调（严格终态守卫：仅 {@code TRADE_SUCCESS}/{@code TRADE_FINISHED} 触发支付成功监听，{@code TRADE_CLOSED} 路由至 {@code onOrderClosed}，其余非成功态静默忽略）
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

        PayTradeState state = callback.getTradeState();
        if (state == PayTradeState.CLOSED || state == PayTradeState.REVOKED) {
            OrderClosedCallback closedCallback = toAlipayOrderClosedCallback(appKeyOrId, params, callback);
            String outTradeNo = closedCallback.getOutTradeNo();
            if (!hasText(outTradeNo)) {
                return buildAlipayResponse("fail", PayCallbackResult.fail("缺少商户订单号"), null, null, closedCallback, null);
            }
            return dispatchAlipayWithLock(
                    "close",
                    PayChannel.ALIPAY.name() + ":CLOSED:" + outTradeNo,
                    outTradeNo,
                    () -> dispatchOrderClosed(closedCallback),
                    null, null, closedCallback, null
            );
        }

        if (state != PayTradeState.SUCCESS) {
            log.info("Ignoring non-success Alipay payment notification: outTradeNo={}, tradeState={}",
                    callback.getOutTradeNo(), state);
            return buildAlipayResponse("success",
                    PayCallbackResult.ignore("忽略非支付成功终态通知: " + state),
                    null, null, null, null);
        }

        String outTradeNo = callback.getOutTradeNo();
        if (!hasText(outTradeNo)) {
            return buildAlipayResponse("fail", PayCallbackResult.fail("缺少商户订单号"), callback, null);
        }
        return dispatchAlipayWithLock(
                "pay",
                PayChannel.ALIPAY.name() + ":" + outTradeNo,
                outTradeNo,
                () -> dispatch(callback),
                callback, null, null, null
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
     * 判断当前支付宝回调参数是否属于退款通知（必须具备正退款金额，且携带退款时间戳 {@code gmt_refund}、退款业务单号或明确退款状态，防止全额退款二次关单通知误路由）
     *
     * @param params 支付宝回调表单参数字典
     * @return true 表示为退款通知
     */
    public static boolean isAlipayRefundNotification(Map<String, String> params) {
        if (params == null || params.isEmpty()) {
            return false;
        }
        if (!hasPositiveAmount(params.get("refund_fee"))) {
            return false;
        }
        return hasText(params.get("gmt_refund"))
                || hasText(params.get("out_biz_no"))
                || hasText(params.get("out_request_no"))
                || hasText(params.get("refund_status"));
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

        String buyerId = firstNonBlankText(params.get("buyer_id"), params.get("buyer_open_id"));
        String payTime = firstNonBlankText(params.get("gmt_payment"), params.get("notify_time"));

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
        String outRefundNo = firstNonBlankText(params.get("out_biz_no"), params.get("out_request_no"), outTradeNo);

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

        String gmtRefund = params.get("gmt_refund");
        String refundTime = firstNonBlankText(gmtRefund, params.get("notify_time"));
        String refundStatus = resolveAlipayRefundStatus(params, gmtRefund, refundAmount);

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
                .refundStatus(refundStatus)
                .rawPayload(Collections.unmodifiableMap(params))
                .build();
    }

    private static String resolveAlipayRefundStatus(Map<String, String> params, String gmtRefund, BigDecimal refundAmount) {
        String explicitStatus = params.get("refund_status");
        if (hasText(explicitStatus)) {
            return explicitStatus.trim();
        }
        if (hasText(gmtRefund) && refundAmount != null && refundAmount.compareTo(BigDecimal.ZERO) > 0) {
            return "REFUND_SUCCESS";
        }
        String tradeStatus = params.get("trade_status");
        return hasText(tradeStatus) ? tradeStatus.trim() : "PROCESSING";
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
        return dispatchToListeners("payment", callback.getOutTradeNo(),
                listener -> listener.supports(callback),
                listener -> listener.onPaymentSuccess(callback));
    }

    /**
     * 派发退款成功异步回调事件给业务监听器
     *
     * @param callback 标准化退款成功回调事件
     * @return 业务处理结果
     */
    public PayCallbackResult dispatchRefund(RefundSuccessCallback callback) {
        return dispatchToListeners("refund", callback.getOutRefundNo(),
                listener -> listener.supports(callback),
                listener -> listener.onRefundSuccess(callback));
    }

    /**
     * 派发订单关闭异步回调事件给业务监听器
     *
     * @param callback 标准化订单关闭回调事件
     * @return 业务处理结果
     */
    public PayCallbackResult dispatchOrderClosed(OrderClosedCallback callback) {
        return dispatchToListeners("order-closed", callback.getOutTradeNo(),
                listener -> listener.supports(callback),
                listener -> listener.onOrderClosed(callback));
    }

    /**
     * 派发商家转账结果异步回调事件给业务监听器
     *
     * @param callback 标准化商家转账回调事件
     * @return 业务处理结果
     */
    public PayCallbackResult dispatchTransfer(TransferNotifyCallback callback) {
        return dispatchToListeners("transfer", firstNonBlankText(callback.getOutBizNo(), callback.getOrderId()),
                listener -> listener.supports(callback),
                listener -> listener.onTransferNotify(callback));
    }

    private PayCallbackResult dispatchToListeners(String eventLabel,
                                                    String bizNo,
                                                    Predicate<PayCallbackListener> supportsPredicate,
                                                    Function<PayCallbackListener, PayCallbackResult> invoker) {
        if (listeners.isEmpty()) {
            log.info("No PayCallbackListener registered in application context, auto ACK {} callback for [{}]",
                    eventLabel, bizNo);
            return PayCallbackResult.ignore("未注册任何回调监听器");
        }

        PayCallbackResult lastResult = PayCallbackResult.success();
        for (PayCallbackListener listener : listeners) {
            if (supportsPredicate.test(listener)) {
                try {
                    PayCallbackResult res = invoker.apply(listener);
                    if (res != null) {
                        if (res.isFail()) {
                            return res;
                        }
                        lastResult = res;
                    }
                } catch (Exception e) {
                    log.error("PayCallbackListener [{}] threw exception processing {} for [{}]: {}",
                            listener.getClass().getName(), eventLabel, bizNo, e.getMessage(), e);
                    return PayCallbackResult.fail("监听器执行异常: " + e.getMessage());
                }
            }
        }
        return lastResult;
    }

    private PayCallbackResponse dispatchWechatWithLock(String scene,
                                                       String lockKey,
                                                       String bizNo,
                                                       Supplier<PayCallbackResult> dispatchAction,
                                                       PaymentSuccessCallback paymentCallback,
                                                       RefundSuccessCallback refundCallback,
                                                       OrderClosedCallback orderClosedCallback,
                                                       TransferNotifyCallback transferCallback) {
        return processWithLock(
                lockKey,
                dispatchAction,
                () -> {
                    log.warn("WeChat {} callback lock contention detected for key [{}], responding FAIL to trigger retry", scene, lockKey);
                    return buildWechatResponse(HttpStatus.INTERNAL_SERVER_ERROR.value(), "FAIL",
                            "并发防重锁冲突，等待下一次重试", PayCallbackResult.fail("并发防重锁冲突"),
                            paymentCallback, refundCallback, orderClosedCallback, transferCallback);
                },
                result -> {
                    if (result.isFail()) {
                        log.warn("WeChat {} callback listener returned FAIL for bizNo [{}], reason: {}",
                                scene, bizNo, result.getMessage());
                        return buildWechatResponse(HttpStatus.INTERNAL_SERVER_ERROR.value(), "FAIL",
                                result.getMessage(), result, paymentCallback, refundCallback, orderClosedCallback, transferCallback);
                    }
                    return buildWechatResponse(HttpStatus.OK.value(), "SUCCESS", "成功",
                            result, paymentCallback, refundCallback, orderClosedCallback, transferCallback);
                }
        );
    }

    private PayCallbackResponse dispatchAlipayWithLock(String scene,
                                                       String lockKey,
                                                       String bizNo,
                                                       Supplier<PayCallbackResult> dispatchAction,
                                                       PaymentSuccessCallback paymentCallback,
                                                       RefundSuccessCallback refundCallback,
                                                       OrderClosedCallback orderClosedCallback,
                                                       TransferNotifyCallback transferCallback) {
        return processWithLock(
                lockKey,
                dispatchAction,
                () -> {
                    log.warn("Alipay {} callback lock contention detected for key [{}], responding fail to trigger retry", scene, lockKey);
                    return buildAlipayResponse("fail", PayCallbackResult.fail("并发防重锁冲突"),
                            paymentCallback, refundCallback, orderClosedCallback, transferCallback);
                },
                result -> {
                    if (result.isFail()) {
                        log.warn("Alipay {} callback listener returned FAIL for bizNo [{}], reason: {}",
                                scene, bizNo, result.getMessage());
                        return buildAlipayResponse("fail", result, paymentCallback, refundCallback, orderClosedCallback, transferCallback);
                    }
                    return buildAlipayResponse("success", result, paymentCallback, refundCallback, orderClosedCallback, transferCallback);
                }
        );
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
                                                    RefundSuccessCallback refundCallback,
                                                    OrderClosedCallback orderClosedCallback,
                                                    TransferNotifyCallback transferCallback) {
        String json = String.format("{\"code\":\"%s\",\"message\":\"%s\"}", code, escapeJson(message));
        return PayCallbackResponse.builder()
                .httpStatus(httpStatus)
                .contentType(JSON_CONTENT_TYPE)
                .responseBody(json)
                .result(result != null ? result : (httpStatus == 200 ? PayCallbackResult.success() : PayCallbackResult.fail(message)))
                .callback(callback)
                .refundCallback(refundCallback)
                .orderClosedCallback(orderClosedCallback)
                .transferCallback(transferCallback)
                .build();
    }

    private PayCallbackResponse buildWechatResponse(int httpStatus, String code, String message,
                                                    PayCallbackResult result,
                                                    PaymentSuccessCallback callback,
                                                    RefundSuccessCallback refundCallback) {
        return buildWechatResponse(httpStatus, code, message, result, callback, refundCallback, null, null);
    }

    private PayCallbackResponse buildAlipayResponse(String responseText, PayCallbackResult result,
                                                    PaymentSuccessCallback callback,
                                                    RefundSuccessCallback refundCallback,
                                                    OrderClosedCallback orderClosedCallback,
                                                    TransferNotifyCallback transferCallback) {
        return PayCallbackResponse.builder()
                .httpStatus(HttpStatus.OK.value())
                .contentType(TEXT_CONTENT_TYPE)
                .responseBody(responseText)
                .result(result != null ? result : ("success".equals(responseText) ? PayCallbackResult.success() : PayCallbackResult.fail("fail")))
                .callback(callback)
                .refundCallback(refundCallback)
                .orderClosedCallback(orderClosedCallback)
                .transferCallback(transferCallback)
                .build();
    }

    private PayCallbackResponse buildAlipayResponse(String responseText, PayCallbackResult result,
                                                    PaymentSuccessCallback callback,
                                                    RefundSuccessCallback refundCallback) {
        return buildAlipayResponse(responseText, result, callback, refundCallback, null, null);
    }

    private static boolean hasText(String value) {
        return value != null && !value.isBlank();
    }

    private static boolean hasPositiveAmount(String amountText) {
        if (!hasText(amountText)) {
            return false;
        }
        try {
            return new BigDecimal(amountText.trim()).compareTo(BigDecimal.ZERO) > 0;
        } catch (NumberFormatException e) {
            return false;
        }
    }

    private static String firstNonBlankText(String... candidates) {
        if (candidates == null) {
            return null;
        }
        for (String candidate : candidates) {
            if (hasText(candidate)) {
                return candidate.trim();
            }
        }
        return null;
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

    /**
     * 微信新版商家转账单据终态通知 (MCHTRANSFER.BILL.FINISHED) 解密载荷 DTO
     * <p>
     * 字段名遵循 Gson {@code LOWER_CASE_WITH_UNDERSCORES} 自动映射规则。
     * </p>
     */
    private static class WechatTransferBillNotification {
        private String mchId;
        private String outBillNo;
        private String transferBillNo;
        private String state;
        private Long transferAmount;
        private String openid;
        private String failReason;
        private String createTime;
        private String updateTime;

        public String getMchId() {
            return mchId;
        }

        public String getOutBillNo() {
            return outBillNo;
        }

        public String getTransferBillNo() {
            return transferBillNo;
        }

        public String getState() {
            return state;
        }

        public Long getTransferAmount() {
            return transferAmount;
        }

        public String getOpenid() {
            return openid;
        }

        public String getFailReason() {
            return failReason;
        }

        public String getCreateTime() {
            return createTime;
        }

        public String getUpdateTime() {
            return updateTime;
        }
    }
}
