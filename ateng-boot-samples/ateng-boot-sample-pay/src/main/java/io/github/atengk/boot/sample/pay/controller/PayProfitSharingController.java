package io.github.atengk.boot.sample.pay.controller;

import com.wechat.pay.java.service.profitsharing.model.AddReceiverRequest;
import com.wechat.pay.java.service.profitsharing.model.AddReceiverResponse;
import com.wechat.pay.java.service.profitsharing.model.OrdersEntity;
import com.wechat.pay.java.service.profitsharing.model.ReceiverRelationType;
import com.wechat.pay.java.service.profitsharing.model.ReceiverType;
import com.wechat.pay.java.service.profitsharing.model.UnfreezeOrderRequest;
import io.github.atengk.boot.core.model.Result;
import io.github.atengk.boot.pay.core.profitsharing.WechatProfitSharingService;
import io.github.atengk.boot.sample.pay.dto.ProfitSharingReceiverDTO;
import io.github.atengk.boot.sample.pay.dto.ProfitSharingUnfreezeDTO;
import jakarta.validation.Valid;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 微信资金分账场景控制器
 *
 * @author Ateng
 * @since 2026-10-24
 */
@Slf4j
@Validated
@RestController
@RequestMapping("/pay/profit-sharing")
public class PayProfitSharingController {

    private final WechatProfitSharingService profitSharingService;

    public PayProfitSharingController(ObjectProvider<WechatProfitSharingService> provider) {
        this.profitSharingService = provider.getIfAvailable();
    }

    /**
     * 添加分账接收方
     */
    @PostMapping("/receiver/add")
    public Result<AddReceiverResponse> addReceiver(@Valid @RequestBody ProfitSharingReceiverDTO dto) {
        if (profitSharingService == null) {
            return Result.failure("微信资金分账特性未启用");
        }
        log.info("Adding profit sharing receiver: account={}, type={}", dto.getAccount(), dto.getType());
        AddReceiverRequest request = new AddReceiverRequest();
        request.setType(ReceiverType.valueOf(dto.getType().toUpperCase()));
        request.setAccount(dto.getAccount());
        request.setName(dto.getName());
        request.setRelationType(ReceiverRelationType.valueOf(dto.getRelationType().toUpperCase()));

        AddReceiverResponse response = profitSharingService.addReceiver(dto.getMerchantKey(), request);
        return Result.success(response);
    }

    /**
     * 解冻分账剩余资金
     */
    @PostMapping("/order/unfreeze")
    public Result<OrdersEntity> unfreezeOrder(@Valid @RequestBody ProfitSharingUnfreezeDTO dto) {
        if (profitSharingService == null) {
            return Result.failure("微信资金分账特性未启用");
        }
        log.info("Unfreezing profit sharing order: outOrderNo={}, transactionId={}",
                dto.getOutOrderNo(), dto.getTransactionId());

        UnfreezeOrderRequest request = new UnfreezeOrderRequest();
        request.setOutOrderNo(dto.getOutOrderNo());
        request.setTransactionId(dto.getTransactionId());
        request.setDescription(dto.getDescription());

        OrdersEntity entity = profitSharingService.unfreezeOrder(dto.getMerchantKey(), request);
        return Result.success(entity);
    }
}
