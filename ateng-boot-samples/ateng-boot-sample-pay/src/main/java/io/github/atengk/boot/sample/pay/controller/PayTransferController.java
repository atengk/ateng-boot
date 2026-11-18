package io.github.atengk.boot.sample.pay.controller;

import io.github.atengk.boot.core.model.Result;
import io.github.atengk.boot.pay.core.PayTemplate;
import io.github.atengk.boot.pay.model.request.TransferSceneReportInfo;
import io.github.atengk.boot.pay.model.request.UnifiedTransferQueryRequest;
import io.github.atengk.boot.pay.model.request.UnifiedTransferRequest;
import io.github.atengk.boot.pay.model.response.PayTransferResponse;
import io.github.atengk.boot.sample.pay.dto.PayTransferCancelDTO;
import io.github.atengk.boot.sample.pay.dto.PayTransferDTO;
import io.github.atengk.boot.sample.pay.dto.PayTransferQueryDTO;
import jakarta.validation.Valid;
import lombok.extern.slf4j.Slf4j;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
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
@RequestMapping("/sample/pay/transfer")
public class PayTransferController {

    private final PayTemplate payTemplate;

    public PayTransferController(PayTemplate payTemplate) {
        this.payTemplate = Objects.requireNonNull(payTemplate, "PayTemplate 不能为空");
    }

    /**
     * 发起单笔转账（默认走微信新版用户确认模式商家转账）
     */
    @PostMapping("/apply")
    public Result<PayTransferResponse> applyTransfer(@Valid @RequestBody PayTransferDTO dto) {
        log.info("Processing transfer request: channel={}, outBizNo={}, amount={}, transferSceneId={}",
                dto.getChannel(), dto.getOutBizNo(), dto.getAmount(), dto.getTransferSceneId());

        List<TransferSceneReportInfo> reportInfos = dto.getTransferSceneReportInfos() == null
                ? null
                : dto.getTransferSceneReportInfos().stream()
                .map(item -> TransferSceneReportInfo.builder()
                        .infoType(item.getInfoType())
                        .infoContent(item.getInfoContent())
                        .build())
                .toList();

        UnifiedTransferRequest request = UnifiedTransferRequest.builder()
                .channel(dto.getChannel())
                .merchantKey(dto.getMerchantKey())
                .outBizNo(dto.getOutBizNo())
                .amount(dto.getAmount())
                .title(dto.getTitle())
                .remark(dto.getRemark())
                .payeeAccount(dto.getPayeeAccount())
                .payeeRealName(dto.getPayeeRealName())
                .payeeIdentityType(dto.getPayeeIdentityType())
                .notifyUrl(dto.getNotifyUrl())
                .transferSceneId(dto.getTransferSceneId())
                .transferSceneReportInfos(reportInfos)
                .legacyBatchMode(dto.getLegacyBatchMode())
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

    /**
     * 主动撤销单笔待确认商家转账单
     *
     * @param dto 撤销转账请求参数
     * @return 标准化转账响应（含 CANCELLED 终态）
     */
    @PostMapping("/cancel")
    public Result<PayTransferResponse> cancelTransfer(@Valid @RequestBody PayTransferCancelDTO dto) {
        log.info("Cancelling transfer bill: channel={}, merchantKey={}, outBizNo={}",
                dto.getChannel(), dto.getMerchantKey(), dto.getOutBizNo());

        PayTransferResponse response = payTemplate.cancelTransfer(
                dto.getChannel(), dto.getMerchantKey(), dto.getOutBizNo());
        return Result.success(response);
    }
}
