package io.github.atengk.boot.sample.pay.controller;

import io.github.atengk.boot.core.model.Result;
import io.github.atengk.boot.pay.core.PayTemplate;
import io.github.atengk.boot.pay.enums.PayTradeType;
import io.github.atengk.boot.pay.model.request.UnifiedCloseRequest;
import io.github.atengk.boot.pay.model.request.UnifiedPayRequest;
import io.github.atengk.boot.pay.model.request.UnifiedQueryRequest;
import io.github.atengk.boot.pay.model.response.PayResponse;
import io.github.atengk.boot.sample.pay.dto.PayCheckoutDTO;
import io.github.atengk.boot.sample.pay.dto.PayOrderCloseDTO;
import io.github.atengk.boot.sample.pay.dto.PayOrderQueryDTO;
import jakarta.validation.Valid;
import lombok.extern.slf4j.Slf4j;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Objects;

/**
 * 支付场景统一下单收银控制器
 * <p>
 * 严格遵循显式强类型契约 (ADR-0002) 与传输层实体隔离 (ADR-0010)，对外统一返回 {@link Result}。
 * 覆盖 Native 扫码、JSAPI 小程序/公众号、H5 移动网页、App 调起、订单状态查询与主动关单全场景。
 * </p>
 *
 * @author Ateng
 * @since 2026-10-23
 */
@Slf4j
@Validated
@RestController
@RequestMapping("/pay")
public class PayCheckoutController {

    private final PayTemplate payTemplate;

    public PayCheckoutController(PayTemplate payTemplate) {
        this.payTemplate = Objects.requireNonNull(payTemplate, "PayTemplate 不能为空");
    }

    /**
     * Native 扫码收单 (生成付款二维码内容链接)
     *
     * @param dto 下单请求参数
     * @return 扁平统一出参（包含 qrCode 字段）
     */
    @PostMapping("/checkout/native")
    public Result<PayResponse> checkoutNative(@Valid @RequestBody PayCheckoutDTO dto) {
        log.info("Processing Native QR payment request: channel={}, outTradeNo={}, amount={}",
                dto.getChannel(), dto.getOutTradeNo(), dto.getAmount());

        UnifiedPayRequest request = UnifiedPayRequest.builder()
                .channel(dto.getChannel())
                .tradeType(PayTradeType.NATIVE)
                .outTradeNo(dto.getOutTradeNo())
                .amount(dto.getAmount())
                .subject(dto.getSubject())
                .merchantKey(dto.getMerchantKey())
                .notifyUrl(dto.getNotifyUrl())
                .attach(dto.getAttach())
                .build();

        PayResponse response = payTemplate.pay(request);
        return Result.success(response);
    }

    /**
     * JSAPI 调起收单 (小程序 / 公众号支付，生成服务端二次加签 5 元素调起包)
     *
     * @param dto 下单请求参数
     * @return 扁平统一出参（包含 wechatJsapi 调起包）
     */
    @PostMapping("/checkout/jsapi")
    public Result<PayResponse> checkoutJsapi(@Valid @RequestBody PayCheckoutDTO dto) {
        log.info("Processing JSAPI payment request: channel={}, outTradeNo={}, amount={}, openid={}",
                dto.getChannel(), dto.getOutTradeNo(), dto.getAmount(), dto.getOpenid());

        UnifiedPayRequest request = UnifiedPayRequest.builder()
                .channel(dto.getChannel())
                .tradeType(PayTradeType.JSAPI)
                .outTradeNo(dto.getOutTradeNo())
                .amount(dto.getAmount())
                .subject(dto.getSubject())
                .openid(dto.getOpenid())
                .merchantKey(dto.getMerchantKey())
                .notifyUrl(dto.getNotifyUrl())
                .attach(dto.getAttach())
                .build();

        PayResponse response = payTemplate.pay(request);
        return Result.success(response);
    }

    /**
     * H5 移动网页收单 (生成手机网页跳转支付链接)
     *
     * @param dto 下单请求参数
     * @return 扁平统一出参（包含 payUrl 或 formHtml 字段）
     */
    @PostMapping("/checkout/h5")
    public Result<PayResponse> checkoutH5(@Valid @RequestBody PayCheckoutDTO dto) {
        log.info("Processing H5 web payment request: channel={}, outTradeNo={}, amount={}",
                dto.getChannel(), dto.getOutTradeNo(), dto.getAmount());

        UnifiedPayRequest request = UnifiedPayRequest.builder()
                .channel(dto.getChannel())
                .tradeType(PayTradeType.H5)
                .outTradeNo(dto.getOutTradeNo())
                .amount(dto.getAmount())
                .subject(dto.getSubject())
                .merchantKey(dto.getMerchantKey())
                .notifyUrl(dto.getNotifyUrl())
                .returnUrl(dto.getReturnUrl())
                .attach(dto.getAttach())
                .build();

        PayResponse response = payTemplate.pay(request);
        return Result.success(response);
    }

    /**
     * App 客户端调起收单 (生成移动 App 加签调起串或 5 元素参数包)
     *
     * @param dto 下单请求参数
     * @return 扁平统一出参（包含 orderString 或 wechatApp 字段）
     */
    @PostMapping("/checkout/app")
    public Result<PayResponse> checkoutApp(@Valid @RequestBody PayCheckoutDTO dto) {
        log.info("Processing App SDK payment request: channel={}, outTradeNo={}, amount={}",
                dto.getChannel(), dto.getOutTradeNo(), dto.getAmount());

        UnifiedPayRequest request = UnifiedPayRequest.builder()
                .channel(dto.getChannel())
                .tradeType(PayTradeType.APP)
                .outTradeNo(dto.getOutTradeNo())
                .amount(dto.getAmount())
                .subject(dto.getSubject())
                .merchantKey(dto.getMerchantKey())
                .notifyUrl(dto.getNotifyUrl())
                .attach(dto.getAttach())
                .build();

        PayResponse response = payTemplate.pay(request);
        return Result.success(response);
    }

    /**
     * 主动查询订单交易状态
     *
     * @param dto 查询请求入参
     * @return 交易状态出参
     */
    @PostMapping("/order/query")
    public Result<PayResponse> queryOrder(@Valid @RequestBody PayOrderQueryDTO dto) {
        log.info("Querying payment order status: channel={}, outTradeNo={}, transactionId={}",
                dto.getChannel(), dto.getOutTradeNo(), dto.getTransactionId());

        UnifiedQueryRequest request = UnifiedQueryRequest.builder()
                .channel(dto.getChannel())
                .outTradeNo(dto.getOutTradeNo())
                .transactionId(dto.getTransactionId())
                .merchantKey(dto.getMerchantKey())
                .build();

        PayResponse response = payTemplate.queryOrder(request);
        return Result.success(response);
    }

    /**
     * 主动关闭未付款订单
     *
     * @param dto 关单请求入参
     * @return 关单操作响应
     */
    @PostMapping("/order/close")
    public Result<PayResponse> closeOrder(@Valid @RequestBody PayOrderCloseDTO dto) {
        log.info("Closing unpaid payment order: channel={}, outTradeNo={}",
                dto.getChannel(), dto.getOutTradeNo());

        UnifiedCloseRequest request = UnifiedCloseRequest.builder()
                .channel(dto.getChannel())
                .outTradeNo(dto.getOutTradeNo())
                .merchantKey(dto.getMerchantKey())
                .build();

        PayResponse response = payTemplate.closeOrder(request);
        return Result.success(response);
    }
}
