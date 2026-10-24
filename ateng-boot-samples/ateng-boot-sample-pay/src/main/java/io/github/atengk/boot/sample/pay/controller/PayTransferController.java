package io.github.atengk.boot.sample.pay.controller;

import io.github.atengk.boot.core.model.Result;
import io.github.atengk.boot.pay.core.PayTemplate;
import io.github.atengk.boot.pay.model.request.UnifiedTransferQueryRequest;
import io.github.atengk.boot.pay.model.request.UnifiedTransferRequest;
import io.github.atengk.boot.pay.model.response.PayTransferResponse;
import io.github.atengk.boot.sample.pay.dto.PayTransferDTO;
import io.github.atengk.boot.sample.pay.dto.PayTransferQueryDTO;
import jakarta.validation.Valid;
import lombok.extern.slf4j.Slf4j;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Objects;

/**
 * 单笔资金转账场景控制器
 *
 * @author Ateng
 * @since 2026-10-24
 */
@Slf4j
@Validated
@RestController
@RequestMapping("/pay/transfer")
public class PayTransferController {

    private final PayTemplate payTemplate;

    public PayTransferController(PayTemplate payTemplate) {
        this.payTemplate = Objects.requireNonNull(payTemplate, "PayTemplate 不能为空");
    }

    /**
     * 发起单笔转账
     */
    @PostMapping("/apply")
    public Result<PayTransferResponse> applyTransfer(@Valid @RequestBody PayTransferDTO dto) {
        log.info("Processing transfer request: channel={}, outBizNo={}, amount={}, payeeAccount={}",
                dto.getChannel(), dto.getOutBizNo(), dto.getAmount(), dto.getPayeeAccount());

        UnifiedTransferRequest request = UnifiedTransferRequest.builder()
                .channel(dto.getChannel())
                .merchantKey(dto.getMerchantKey())
                .outBizNo(dto.getOutBizNo())
                .amount(dto.getAmount())
                .title(dto.getTitle())
                .payeeAccount(dto.getPayeeAccount())
                .payeeRealName(dto.getPayeeRealName())
                .build();

        PayTransferResponse response = payTemplate.transfer(request);
        return Result.success(response);
    }

    /**
     * 查询单笔转账状态
     */
    @PostMapping("/query")
    public Result<PayTransferResponse> queryTransfer(@Valid @RequestBody PayTransferQueryDTO dto) {
        log.info("Processing transfer query: channel={}, outBizNo={}, orderId={}",
                dto.getChannel(), dto.getOutBizNo(), dto.getOrderId());

        UnifiedTransferQueryRequest request = UnifiedTransferQueryRequest.builder()
                .channel(dto.getChannel())
                .merchantKey(dto.getMerchantKey())
                .outBizNo(dto.getOutBizNo())
                .orderId(dto.getOrderId())
                .build();

        PayTransferResponse response = payTemplate.queryTransfer(request);
        return Result.success(response);
    }
}
