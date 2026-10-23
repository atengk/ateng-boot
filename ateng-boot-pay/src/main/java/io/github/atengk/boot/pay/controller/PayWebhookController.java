package io.github.atengk.boot.pay.controller;

import io.github.atengk.boot.pay.core.callback.PayCallbackHandler;
import io.github.atengk.boot.pay.core.merchant.NamedMerchantRegistry;
import io.github.atengk.boot.pay.model.callback.PayCallbackResponse;
import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Objects;

/**
 * 支付异步 Webhook 回调统配控制器
 * <p>
 * 默认映射统一网关端点 {@code /ateng-pay/callback/{channel}/{merchantId}}，
 * 自动拦截微信支付与支付宝的异步通知请求，执行底层统一验签、解密、并发防重锁与强类型业务监听器分发，
 * 并向渠道回复合规的 ACK 报文（支持官方阶梯式重试机制）。
 * </p>
 *
 * @author Ateng
 * @since 2026-10-23
 */
@RestController
@RequestMapping("${ateng.boot.pay.callback.path-prefix:/ateng-pay/callback}")
public class PayWebhookController {

    private static final Logger log = LoggerFactory.getLogger(PayWebhookController.class);

    private final PayCallbackHandler payCallbackHandler;

    public PayWebhookController(PayCallbackHandler payCallbackHandler) {
        this.payCallbackHandler = Objects.requireNonNull(payCallbackHandler, "PayCallbackHandler 不能为空");
    }

    /**
     * 统一接收并处理各支付渠道的异步 Webhook 回调
     *
     * @param channel    支付渠道标识（wechat / alipay）
     * @param merchantId 商户键或商户号 / 应用 AppId（可选，默认 default）
     * @param request    Servlet 原生请求对象
     * @return 官方标准渠道 ACK 响应
     */
    @PostMapping(value = {"/{channel}/{merchantId}", "/{channel}"})
    public ResponseEntity<String> handleCallback(
            @PathVariable("channel") String channel,
            @PathVariable(value = "merchantId", required = false) String merchantId,
            HttpServletRequest request) {

        String effectiveMerchantId = (merchantId != null && !merchantId.isBlank())
                ? merchantId
                : NamedMerchantRegistry.DEFAULT_KEY;

        log.info("Received incoming payment webhook notification: channel=[{}], merchantId=[{}]",
                channel, effectiveMerchantId);

        String normalizedChannel = channel != null ? channel.trim().toLowerCase() : "";
        PayCallbackResponse response;

        switch (normalizedChannel) {
            case "wechat", "wechatpay", "wechat_pay", "wxpay", "wx" ->
                    response = payCallbackHandler.handleWechat(effectiveMerchantId, request);
            case "alipay", "ali", "alipay_trade" ->
                    response = payCallbackHandler.handleAlipay(effectiveMerchantId, request);
            default -> {
                log.warn("Unsupported payment webhook channel: [{}]", channel);
                return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                        .body("Unsupported payment callback channel: " + channel);
            }
        }

        return response.toResponseEntity();
    }
}
