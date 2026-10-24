package io.github.atengk.boot.pay.core.executor;

import io.github.atengk.boot.pay.config.properties.BarcodePayProperties;
import io.github.atengk.boot.pay.core.PayTemplate;
import io.github.atengk.boot.pay.enums.PayChannel;
import io.github.atengk.boot.pay.enums.PayResultCode;
import io.github.atengk.boot.pay.enums.PayTradeState;
import io.github.atengk.boot.pay.enums.PayTradeType;
import io.github.atengk.boot.pay.exception.PayException;
import io.github.atengk.boot.pay.exception.PayNetworkException;
import io.github.atengk.boot.pay.model.request.UnifiedCancelRequest;
import io.github.atengk.boot.pay.model.request.UnifiedPayRequest;
import io.github.atengk.boot.pay.model.request.UnifiedQueryRequest;
import io.github.atengk.boot.pay.model.response.PayResponse;
import lombok.extern.slf4j.Slf4j;

import java.time.Duration;
import java.util.Objects;
import java.util.function.Consumer;

/**
 * 线下收银台条码支付执行器 (BarcodePayExecutor)
 * <p>
 * 专职封装线下 POS 机、扫码枪、收银台等场景下条码付（被扫，即 {@code payBarcode}）的完整业务闭环：
 * <ol>
 *     <li><b>即时成功短路</b>：若首次调用付款码扣款直接成功（如免密小额支付），立即返回 {@link PayTradeState#SUCCESS}，零额外等待；</li>
 *     <li><b>同步阶梯式轮询 (Stepped Polling)</b>：若返回等待用户输密（微信 {@code USERPAYING}、支付宝 {@code 10003/WAIT_BUYER_PAY}），
 *         在超时窗口内按步长（默认间隔 3s）持续轮询查单，一旦用户在手机上输密成功即刻短路返回；</li>
 *     <li><b>超时主动撤销冲正 (Auto-Reversal / Cancellation)</b>：若超过最大等待时长（默认 30s）订单仍未成功，
 *         自动触发底层撤销冲正接口（支付宝 {@code AlipayTradeCancelRequest}、微信撤销/关单），
 *         释放用户资金授权，杜绝收银员漏单逃单、收银台卡单与账实不符风险；</li>
 *     <li><b>强类型 Duration 配置</b>：遵循工业级规范，超时与间隔参数采用强类型 {@link Duration}，提供生产高可用合理默认值。</li>
 * </ol>
 * </p>
 *
 * @author Ateng
 * @since 2026-10-24
 */
@Slf4j
public class BarcodePayExecutor {

    private final PayTemplate payTemplate;
    private final BarcodePayProperties defaultProperties;

    /**
     * 可替换的睡眠等待器（供单元测试极速模拟或异步控制）
     */
    private Consumer<Duration> sleeper = duration -> {
        try {
            Thread.sleep(duration.toMillis());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            log.warn("Barcode polling thread was interrupted.");
        }
    };

    public BarcodePayExecutor(PayTemplate payTemplate, BarcodePayProperties defaultProperties) {
        this.payTemplate = Objects.requireNonNull(payTemplate, "PayTemplate 不能为空");
        this.defaultProperties = defaultProperties != null ? defaultProperties : new BarcodePayProperties();
    }

    public BarcodePayExecutor(PayTemplate payTemplate) {
        this(payTemplate, new BarcodePayProperties());
    }

    /**
     * 设置自定义睡眠等待器（主要用于单元测试快速推进时间，避免真实物理等待）
     *
     * @param sleeper 睡眠等待消费函数
     */
    public void setSleeper(Consumer<Duration> sleeper) {
        this.sleeper = Objects.requireNonNull(sleeper, "Sleeper 不能为空");
    }

    /**
     * 执行条码收银支付（使用全局默认配置）
     *
     * @param request 统一收单请求参数
     * @return 最终收单/轮询/撤销冲正结果响应
     */
    public PayResponse execute(UnifiedPayRequest request) {
        return execute(request, defaultProperties.getMaxWaitTime(), defaultProperties.getPollInterval(), defaultProperties.isAutoCancelOnTimeout());
    }

    /**
     * 执行条码收银支付（定制超时时间与轮询间隔）
     *
     * @param request      统一收单请求参数
     * @param maxWaitTime  最大等待时间
     * @param pollInterval 轮询间隔步长
     * @return 最终收单/轮询/撤销冲正结果响应
     */
    public PayResponse execute(UnifiedPayRequest request, Duration maxWaitTime, Duration pollInterval) {
        return execute(request, maxWaitTime, pollInterval, defaultProperties.isAutoCancelOnTimeout());
    }

    /**
     * 执行条码收银支付（定制超时时间、轮询间隔与自动撤销开关）
     *
     * @param request             统一收单请求参数
     * @param maxWaitTime         最大等待时间
     * @param pollInterval        轮询间隔步长
     * @param autoCancelOnTimeout 超时未成功时是否自动发起撤销冲正
     * @return 最终收单/轮询/撤销冲正结果响应
     */
    public PayResponse execute(UnifiedPayRequest request, Duration maxWaitTime, Duration pollInterval, boolean autoCancelOnTimeout) {
        validateRequest(request);

        // 确保 tradeType 固定为 BARCODE
        request.setTradeType(PayTradeType.BARCODE);

        Duration effectiveMaxWait = maxWaitTime != null && !maxWaitTime.isNegative() && !maxWaitTime.isZero()
                ? maxWaitTime
                : defaultProperties.getMaxWaitTime();
        Duration effectiveInterval = pollInterval != null && !pollInterval.isNegative() && !pollInterval.isZero()
                ? pollInterval
                : defaultProperties.getPollInterval();

        String outTradeNo = request.getOutTradeNo();
        PayChannel channel = request.getChannel();

        log.info("BarcodePayExecutor starting: channel={}, outTradeNo={}, amount={}, maxWaitTime={}, pollInterval={}",
                channel, outTradeNo, request.getAmount(), effectiveMaxWait, effectiveInterval);

        // 1. 发起初始条码支付
        PayResponse initialResponse;
        try {
            initialResponse = payTemplate.pay(request);
        } catch (PayNetworkException e) {
            log.warn("Network exception occurred during initial barcode payment for outTradeNo={}, will proceed to polling: {}",
                    outTradeNo, e.getMessage());
            initialResponse = null;
        }

        // 2. 初始扣款直接成功（如小额免密支付）
        if (initialResponse != null && initialResponse.isSuccess() && initialResponse.getTradeState() == PayTradeState.SUCCESS) {
            log.info("Barcode payment succeeded immediately without polling: outTradeNo={}", outTradeNo);
            return initialResponse;
        }

        // 3. 进入同步阶梯轮询流程 (等待用户在手机端输入密码或确认)
        long startTime = System.currentTimeMillis();
        long deadline = startTime + effectiveMaxWait.toMillis();
        PayResponse latestResponse = initialResponse;

        log.info("Barcode payment pending user authentication, entering stepped polling loop: outTradeNo={}, deadline={}ms",
                outTradeNo, effectiveMaxWait.toMillis());

        while (System.currentTimeMillis() < deadline) {
            // 等待间隔步长
            sleeper.accept(effectiveInterval);

            // 查单
            try {
                UnifiedQueryRequest queryReq = UnifiedQueryRequest.builder()
                        .channel(channel)
                        .merchantKey(request.getMerchantKey())
                        .outTradeNo(outTradeNo)
                        .subMchId(request.getSubMchId())
                        .build();

                latestResponse = payTemplate.queryOrder(queryReq);

                if (latestResponse != null && latestResponse.isSuccess() && latestResponse.getTradeState() == PayTradeState.SUCCESS) {
                    long elapsed = System.currentTimeMillis() - startTime;
                    log.info("Barcode payment succeeded during polling window: outTradeNo={}, elapsed={}ms", outTradeNo, elapsed);
                    return latestResponse;
                }

                // 若遇到明确的不可逆失败终态（如订单已关闭或已被撤销），跳出循环不再无谓轮询
                if (latestResponse != null && (latestResponse.getTradeState() == PayTradeState.CLOSED
                        || latestResponse.getTradeState() == PayTradeState.REVOKED
                        || latestResponse.getTradeState() == PayTradeState.PAYERROR)) {
                    log.warn("Barcode payment received definitive failure status during polling: outTradeNo={}, tradeState={}",
                            outTradeNo, latestResponse.getTradeState());
                    break;
                }

            } catch (Exception e) {
                log.warn("Transient error querying barcode payment status for outTradeNo={}, will retry in next interval: {}",
                        outTradeNo, e.getMessage());
            }

            // 检查当前线程是否已中断
            if (Thread.currentThread().isInterrupted()) {
                log.warn("Barcode polling loop interrupted for outTradeNo={}", outTradeNo);
                break;
            }
        }

        // 4. 超时仍未成功或未决，触发主动撤销冲正
        long totalElapsed = System.currentTimeMillis() - startTime;
        log.warn("Barcode payment timed out or ended without success: outTradeNo={}, totalElapsed={}ms, latestState={}",
                outTradeNo, totalElapsed, latestResponse != null ? latestResponse.getTradeState() : "UNKNOWN");

        if (autoCancelOnTimeout) {
            log.info("Auto-reversal enabled, triggering cancelOrder for outTradeNo={}", outTradeNo);
            try {
                UnifiedCancelRequest cancelReq = UnifiedCancelRequest.builder()
                        .channel(channel)
                        .merchantKey(request.getMerchantKey())
                        .outTradeNo(outTradeNo)
                        .subMchId(request.getSubMchId())
                        .build();

                PayResponse cancelResp = payTemplate.cancelOrder(cancelReq);
                log.info("Auto-reversal cancelOrder executed successfully: outTradeNo={}, cancelResult={}",
                        outTradeNo, cancelResp != null ? cancelResp.getTradeState() : "NULL");

                return PayResponse.builder()
                        .channel(channel)
                        .tradeType(PayTradeType.BARCODE)
                        .outTradeNo(outTradeNo)
                        .amount(request.getAmount())
                        .tradeState(PayTradeState.REVOKED)
                        .tradeStateDesc("条码支付等待用户输密超时，系统已自动发起撤销冲正")
                        .success(false)
                        .rawResponse(cancelResp != null ? cancelResp.getRawResponse() : null)
                        .build();
            } catch (Exception cancelEx) {
                log.error("Failed to cancel/reverse barcode payment for outTradeNo={}: {}", outTradeNo, cancelEx.getMessage(), cancelEx);
                throw new PayException(PayResultCode.PAY_GATEWAY_ERROR,
                        "条码支付超时且自动撤销冲正失败: " + cancelEx.getMessage(), cancelEx);
            }
        }

        return latestResponse != null ? latestResponse : PayResponse.builder()
                .channel(channel)
                .tradeType(PayTradeType.BARCODE)
                .outTradeNo(outTradeNo)
                .amount(request.getAmount())
                .tradeState(PayTradeState.NOTPAY)
                .tradeStateDesc("条码支付超时未完成")
                .success(false)
                .build();
    }

    private void validateRequest(UnifiedPayRequest request) {
        if (request == null) {
            throw new PayException(PayResultCode.PAY_PARAM_INVALID, "条码收银支付请求参数不能为空");
        }
        if (request.getOutTradeNo() == null || request.getOutTradeNo().isBlank()) {
            throw new PayException(PayResultCode.PAY_PARAM_INVALID, "商户订单号 [outTradeNo] 不能为空");
        }
        if (request.getAuthCode() == null || request.getAuthCode().isBlank()) {
            throw new PayException(PayResultCode.PAY_PARAM_INVALID, "条码收银必须提供用户付款码授权码 [authCode]");
        }
        if (request.getAmount() == null || request.getAmount().compareTo(java.math.BigDecimal.ZERO) <= 0) {
            throw new PayException(PayResultCode.PAY_AMOUNT_INVALID, "支付金额 [amount] 必须大于 0 元");
        }
    }
}
