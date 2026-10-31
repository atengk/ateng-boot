package io.github.atengk.boot.sample.pay.controller;

import io.github.atengk.boot.core.model.Result;
import io.github.atengk.boot.pay.core.executor.BarcodePayExecutor;
import io.github.atengk.boot.pay.enums.PayTradeType;
import io.github.atengk.boot.pay.model.request.UnifiedPayRequest;
import io.github.atengk.boot.pay.model.response.PayResponse;
import io.github.atengk.boot.sample.pay.dto.PayBarcodeDTO;
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
@RequestMapping("/pay/barcode")
public class PayBarcodeController {

    private final BarcodePayExecutor barcodePayExecutor;

    public PayBarcodeController(BarcodePayExecutor barcodePayExecutor) {
        this.barcodePayExecutor = Objects.requireNonNull(barcodePayExecutor, "BarcodePayExecutor 不能为空");
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
}
