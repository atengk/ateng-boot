package io.github.atengk.boot.sample.pay.controller;

import io.github.atengk.boot.core.model.Result;
import io.github.atengk.boot.pay.core.PayTemplate;
import io.github.atengk.boot.pay.core.bill.PayBillParser;
import io.github.atengk.boot.pay.model.bill.PayBillDownloadRequest;
import io.github.atengk.boot.pay.model.bill.PayBillResult;
import io.github.atengk.boot.pay.model.bill.PayBillSummary;
import io.github.atengk.boot.sample.pay.dto.PayBillQueryDTO;
import jakarta.validation.Valid;
import lombok.extern.slf4j.Slf4j;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.io.InputStream;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 财务对账单流式下载与解析场景控制器
 *
 * @author Ateng
 * @since 2026-10-24
 */
@Slf4j
@Validated
@RestController
@RequestMapping("/pay/bill")
public class PayBillController {

    private final PayTemplate payTemplate;
    private final PayBillParser payBillParser;

    public PayBillController(PayTemplate payTemplate, PayBillParser payBillParser) {
        this.payTemplate = Objects.requireNonNull(payTemplate, "PayTemplate 不能为空");
        this.payBillParser = Objects.requireNonNull(payBillParser, "PayBillParser 不能为空");
    }

    /**
     * 内存全量模式：下载并解析为完整对账结果列表
     */
    @PostMapping("/parse-memory")
    public Result<PayBillResult> parseMemory(@Valid @RequestBody PayBillQueryDTO dto) throws Exception {
        log.info("Processing bill parse (memory mode): channel={}, date={}, type={}",
                dto.getChannel(), dto.getBillDate(), dto.getBillType());

        PayBillDownloadRequest request = PayBillDownloadRequest.builder()
                .channel(dto.getChannel())
                .merchantKey(dto.getMerchantKey())
                .billDate(dto.getBillDate())
                .billType(dto.getBillType())
                .tarType(dto.getTarType())
                .build();

        try (InputStream stream = payTemplate.downloadBillStream(request)) {
            PayBillResult result = payBillParser.parse(stream);
            return Result.success(result);
        }
    }

    /**
     * 零 OOM 模式：边流式解压边逐行回调处理，常驻 O(1) 内存
     */
    @PostMapping("/stream-reconcile")
    public Result<PayBillSummary> streamReconcile(@Valid @RequestBody PayBillQueryDTO dto) throws Exception {
        log.info("Processing bill parse (zero-OOM streaming mode): channel={}, date={}",
                dto.getChannel(), dto.getBillDate());

        PayBillDownloadRequest request = PayBillDownloadRequest.builder()
                .channel(dto.getChannel())
                .merchantKey(dto.getMerchantKey())
                .billDate(dto.getBillDate())
                .billType(dto.getBillType())
                .tarType(dto.getTarType())
                .build();

        AtomicLong counter = new AtomicLong();
        try (InputStream stream = payTemplate.downloadBillStream(request)) {
            PayBillSummary summary = payBillParser.parse(stream, entry -> {
                long c = counter.incrementAndGet();
                log.debug("Streaming entry #{}: outTradeNo={}, amount={}", c, entry.getOutTradeNo(), entry.getTotalAmount());
            });
            log.info("Finished streaming reconciliation, total streamed entries: {}", counter.get());
            return Result.success(summary);
        }
    }
}
