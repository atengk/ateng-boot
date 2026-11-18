package io.github.atengk.boot.sample.pay.controller;

import io.github.atengk.boot.core.model.Result;
import io.github.atengk.boot.pay.core.PayTemplate;
import io.github.atengk.boot.pay.core.executor.BarcodePayExecutor;
import io.github.atengk.boot.pay.enums.PayTradeType;
import io.github.atengk.boot.pay.model.request.UnifiedCancelRequest;
import io.github.atengk.boot.pay.model.request.UnifiedPayRequest;
import io.github.atengk.boot.pay.model.response.PayResponse;
import io.github.atengk.boot.sample.pay.dto.PayBarcodeDTO;
import io.github.atengk.boot.sample.pay.dto.PayOrderCancelDTO;
import jakarta.validation.Valid;
import lombok.extern.slf4j.Slf4j;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Objects;

/**
 * 线下收银台条码支付场景控制器
 *
 * @author Ateng
 * @since 2026-10-24
 */
@Slf4j
@Validated
@RestController
@RequestMapping("/sample/pay/barcode")
public class PayBarcodeController {

    private final BarcodePayExecutor barcodePayExecutor;
    private final PayTemplate payTemplate;

    public PayBarcodeController(BarcodePayExecutor barcodePayExecutor, PayTemplate payTemplate) {
        this.barcodePayExecutor = Objects.requireNonNull(barcodePayExecutor, "BarcodePayExecutor 不能为空");
        this.payTemplate = Objects.requireNonNull(payTemplate, "PayTemplate 不能为空");
    }

    /**
     * 发起条码收银同步轮询扣款
     */
    @PostMapping("/cashier")
    public Result<PayResponse> cashierPay(@Valid @RequestBody PayBarcodeDTO dto) {
        log.info("Processing barcode cashier payment: channel={}, outTradeNo={}, amount={}",
                dto.getChannel(), dto.getOutTradeNo(), dto.getAmount());

        UnifiedPayRequest request = UnifiedPayRequest.builder()
                .channel(dto.getChannel())
                .tradeType(PayTradeType.BARCODE)
                .merchantKey(dto.getMerchantKey())
                .outTradeNo(dto.getOutTradeNo())
                .amount(dto.getAmount())
                .authCode(dto.getAuthCode())
                .subject(dto.getSubject())
                .build();

        PayResponse response;
        if (dto.getMaxWaitTime() != null && dto.getPollInterval() != null) {
            boolean autoCancel = dto.getAutoCancelOnTimeout() != null ? dto.getAutoCancelOnTimeout() : true;
            response = barcodePayExecutor.execute(request, dto.getMaxWaitTime(), dto.getPollInterval(), autoCancel);
        } else {
            response = barcodePayExecutor.execute(request);
        }
        return Result.success(response);
    }

    /**
     * 主动撤销超时或异常订单（如实透传渠道 REVOKED/CLOSED 撤销结果）
     *
     * @param dto 订单撤销请求参数
     * @return 统一支付响应契约
     */
    @PostMapping("/cancel")
    public Result<PayResponse> cancelOrder(@Valid @RequestBody PayOrderCancelDTO dto) {
        log.info("Cancelling order manually: channel={}, outTradeNo={}, transactionId={}",
                dto.getChannel(), dto.getOutTradeNo(), dto.getTransactionId());

        UnifiedCancelRequest request = UnifiedCancelRequest.builder()
                .channel(dto.getChannel())
                .merchantKey(dto.getMerchantKey())
                .outTradeNo(dto.getOutTradeNo())
                .transactionId(dto.getTransactionId())
                .subMchId(dto.getSubMchId())
                .appAuthToken(dto.getAppAuthToken())
                .build();

        PayResponse response = payTemplate.cancelOrder(request);
        return Result.success(response);
    }
}
