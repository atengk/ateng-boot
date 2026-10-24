package io.github.atengk.boot.sample.pay.controller;

import io.github.atengk.boot.core.model.Result;
import io.github.atengk.boot.pay.core.PayTemplate;
import io.github.atengk.boot.pay.model.request.UnifiedRefundQueryRequest;
import io.github.atengk.boot.pay.model.request.UnifiedRefundRequest;
import io.github.atengk.boot.pay.model.response.PayRefundResponse;
import io.github.atengk.boot.sample.pay.dto.PayRefundDTO;
import io.github.atengk.boot.sample.pay.dto.PayRefundQueryDTO;
import jakarta.validation.Valid;
import lombok.extern.slf4j.Slf4j;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Objects;

/**
 * 统一退款场景控制器
 *
 * @author Ateng
 * @since 2026-10-24
 */
@Slf4j
@Validated
@RestController
@RequestMapping("/pay/refund")
public class PayRefundController {

    private final PayTemplate payTemplate;

    public PayRefundController(PayTemplate payTemplate) {
        this.payTemplate = Objects.requireNonNull(payTemplate, "PayTemplate 不能为空");
    }

    /**
     * 发起退款申请
     */
    @PostMapping("/apply")
    public Result<PayRefundResponse> applyRefund(@Valid @RequestBody PayRefundDTO dto) {
        log.info("Processing refund request: channel={}, outTradeNo={}, outRefundNo={}, refundAmount={}",
                dto.getChannel(), dto.getOutTradeNo(), dto.getOutRefundNo(), dto.getRefundAmount());

        UnifiedRefundRequest request = UnifiedRefundRequest.builder()
                .channel(dto.getChannel())
                .merchantKey(dto.getMerchantKey())
                .outTradeNo(dto.getOutTradeNo())
                .transactionId(dto.getTransactionId())
                .outRefundNo(dto.getOutRefundNo())
                .refundAmount(dto.getRefundAmount())
                .totalAmount(dto.getTotalAmount())
                .reason(dto.getReason())
                .build();

        PayRefundResponse response = payTemplate.refund(request);
        return Result.success(response);
    }

    /**
     * 查询退款状态
     */
    @PostMapping("/query")
    public Result<PayRefundResponse> queryRefund(@Valid @RequestBody PayRefundQueryDTO dto) {
        log.info("Processing refund query: channel={}, outRefundNo={}", dto.getChannel(), dto.getOutRefundNo());

        UnifiedRefundQueryRequest request = UnifiedRefundQueryRequest.builder()
                .channel(dto.getChannel())
                .merchantKey(dto.getMerchantKey())
                .outTradeNo(dto.getOutTradeNo())
                .outRefundNo(dto.getOutRefundNo())
                .build();

        PayRefundResponse response = payTemplate.queryRefund(request);
        return Result.success(response);
    }
}
