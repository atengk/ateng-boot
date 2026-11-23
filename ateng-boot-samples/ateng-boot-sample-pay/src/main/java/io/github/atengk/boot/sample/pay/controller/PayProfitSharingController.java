package io.github.atengk.boot.sample.pay.controller;

import com.alipay.api.domain.AlipayTradeOrderSettleModel;
import com.alipay.api.domain.AlipayTradeOrderSettleQueryModel;
import com.alipay.api.domain.AlipayTradeRoyaltyRelationBatchqueryModel;
import com.alipay.api.domain.AlipayTradeRoyaltyRelationBindModel;
import com.alipay.api.domain.AlipayTradeRoyaltyRelationUnbindModel;
import com.alipay.api.domain.AlipayTradeSettleConfirmModel;
import com.alipay.api.domain.OpenApiRoyaltyDetailInfoPojo;
import com.alipay.api.domain.RoyaltyEntity;
import com.alipay.api.domain.SettleDetailInfo;
import com.alipay.api.domain.SettleInfo;
import com.alipay.api.response.AlipayTradeOrderSettleQueryResponse;
import com.alipay.api.response.AlipayTradeOrderSettleResponse;
import com.alipay.api.response.AlipayTradeRoyaltyRelationBatchqueryResponse;
import com.alipay.api.response.AlipayTradeRoyaltyRelationBindResponse;
import com.alipay.api.response.AlipayTradeRoyaltyRelationUnbindResponse;
import com.alipay.api.response.AlipayTradeSettleConfirmResponse;
import com.wechat.pay.java.service.profitsharing.model.AddReceiverRequest;
import com.wechat.pay.java.service.profitsharing.model.AddReceiverResponse;
import com.wechat.pay.java.service.profitsharing.model.CreateOrderReceiver;
import com.wechat.pay.java.service.profitsharing.model.CreateOrderRequest;
import com.wechat.pay.java.service.profitsharing.model.CreateReturnOrderRequest;
import com.wechat.pay.java.service.profitsharing.model.DeleteReceiverRequest;
import com.wechat.pay.java.service.profitsharing.model.DeleteReceiverResponse;
import com.wechat.pay.java.service.profitsharing.model.OrdersEntity;
import com.wechat.pay.java.service.profitsharing.model.QueryMerchantRatioRequest;
import com.wechat.pay.java.service.profitsharing.model.QueryMerchantRatioResponse;
import com.wechat.pay.java.service.profitsharing.model.QueryOrderAmountRequest;
import com.wechat.pay.java.service.profitsharing.model.QueryOrderAmountResponse;
import com.wechat.pay.java.service.profitsharing.model.QueryOrderRequest;
import com.wechat.pay.java.service.profitsharing.model.QueryReturnOrderRequest;
import com.wechat.pay.java.service.profitsharing.model.ReceiverRelationType;
import com.wechat.pay.java.service.profitsharing.model.ReceiverType;
import com.wechat.pay.java.service.profitsharing.model.ReturnOrdersEntity;
import com.wechat.pay.java.service.profitsharing.model.UnfreezeOrderRequest;
import io.github.atengk.boot.core.code.CommonResultCode;
import io.github.atengk.boot.core.exception.BusinessException;
import io.github.atengk.boot.core.model.Result;
import io.github.atengk.boot.core.util.BusinessAssert;
import io.github.atengk.boot.core.util.DesensitizeUtil;
import io.github.atengk.boot.pay.core.AlipayTemplate;
import io.github.atengk.boot.pay.core.profitsharing.AlipayProfitSharingService;
import io.github.atengk.boot.pay.core.profitsharing.WechatProfitSharingService;
import io.github.atengk.boot.pay.model.request.AlipayPayRequest;
import io.github.atengk.boot.pay.model.response.PayResponse;
import io.github.atengk.boot.sample.pay.dto.AlipayOrderSettleDTO;
import io.github.atengk.boot.sample.pay.dto.AlipayOrderSettleQueryDTO;
import io.github.atengk.boot.sample.pay.dto.AlipayPreFreezeCheckoutDTO;
import io.github.atengk.boot.sample.pay.dto.AlipayRoyaltyRelationDTO;
import io.github.atengk.boot.sample.pay.dto.AlipayRoyaltyRelationQueryDTO;
import io.github.atengk.boot.sample.pay.dto.AlipaySettleConfirmDTO;
import io.github.atengk.boot.sample.pay.dto.ProfitSharingAmountQueryDTO;
import io.github.atengk.boot.sample.pay.dto.ProfitSharingCreateOrderDTO;
import io.github.atengk.boot.sample.pay.dto.ProfitSharingDeleteReceiverDTO;
import io.github.atengk.boot.sample.pay.dto.ProfitSharingMerchantRatioQueryDTO;
import io.github.atengk.boot.sample.pay.dto.ProfitSharingOrderQueryDTO;
import io.github.atengk.boot.sample.pay.dto.ProfitSharingReceiverDTO;
import io.github.atengk.boot.sample.pay.dto.ProfitSharingReturnOrderDTO;
import io.github.atengk.boot.sample.pay.dto.ProfitSharingReturnQueryDTO;
import io.github.atengk.boot.sample.pay.dto.ProfitSharingUnfreezeDTO;
import jakarta.validation.Valid;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.math.RoundingMode;
import java.util.List;
import java.util.Locale;

/**
 * 双渠道资金分账全生命周期场景控制器（覆盖微信 V3 分账与支付宝交易分账结算）
 *
 * @author Ateng
 * @since 2026-10-24
 */
@Slf4j
@Validated
@RestController
@RequestMapping("/sample/pay/profit-sharing")
public class PayProfitSharingController {

    private final WechatProfitSharingService profitSharingService;
    private final AlipayProfitSharingService alipayProfitSharingService;
    private final AlipayTemplate alipayTemplate;

    public PayProfitSharingController(ObjectProvider<WechatProfitSharingService> wechatProvider,
                                      ObjectProvider<AlipayProfitSharingService> alipayProfitSharingProvider,
                                      ObjectProvider<AlipayTemplate> alipayTemplateProvider) {
        this.profitSharingService = wechatProvider.getIfAvailable();
        this.alipayProfitSharingService = alipayProfitSharingProvider.getIfAvailable();
        this.alipayTemplate = alipayTemplateProvider.getIfAvailable();
    }

    // ==================== 1. 微信 V3 资金分账全生命周期 ====================

    /**
     * 添加微信分账接收方
     *
     * @param dto 分账接收方请求参数
     * @return 添加接收方响应结果
     */
    @PostMapping("/receivers/add")
    public Result<AddReceiverResponse> addReceiver(@Valid @RequestBody ProfitSharingReceiverDTO dto) {
        WechatProfitSharingService service = requireWechatService();
        log.info("Adding WeChat profit sharing receiver: account={}, type={}",
                DesensitizeUtil.maskKeepHeadTail(dto.getAccount(), 3, 3), dto.getType());

        AddReceiverRequest request = new AddReceiverRequest();
        request.setType(parseEnum(ReceiverType.class, dto.getType(), "type"));
        request.setAccount(dto.getAccount());
        request.setName(dto.getName());
        request.setRelationType(parseEnum(ReceiverRelationType.class, dto.getRelationType(), "relationType"));

        return Result.success(service.addReceiver(dto.getMerchantKey(), request));
    }

    /**
     * 删除微信分账接收方
     *
     * @param dto 删除分账接收方请求参数
     * @return 删除接收方响应结果
     */
    @PostMapping("/receivers/delete")
    public Result<DeleteReceiverResponse> deleteReceiver(@Valid @RequestBody ProfitSharingDeleteReceiverDTO dto) {
        WechatProfitSharingService service = requireWechatService();
        log.info("Deleting WeChat profit sharing receiver: account={}, type={}",
                DesensitizeUtil.maskKeepHeadTail(dto.getAccount(), 3, 3), dto.getType());

        DeleteReceiverRequest request = new DeleteReceiverRequest();
        request.setType(parseEnum(ReceiverType.class, dto.getType(), "type"));
        request.setAccount(dto.getAccount());

        return Result.success(service.deleteReceiver(dto.getMerchantKey(), request));
    }

    /**
     * 发起微信资金分账请求
     *
     * @param dto 分账请求参数
     * @return 微信分账订单实体
     */
    @PostMapping("/orders/create")
    public Result<OrdersEntity> createOrder(@Valid @RequestBody ProfitSharingCreateOrderDTO dto) {
        WechatProfitSharingService service = requireWechatService();
        log.info("Creating WeChat profit sharing order: outOrderNo={}, transactionId={}",
                dto.getOutOrderNo(), dto.getTransactionId());

        CreateOrderRequest request = new CreateOrderRequest();
        request.setSubMchid(dto.getSubMchid());
        request.setTransactionId(dto.getTransactionId());
        request.setOutOrderNo(dto.getOutOrderNo());
        request.setUnfreezeUnsplit(dto.getUnfreezeUnsplit());
        request.setReceivers(dto.getReceivers().stream().map(item -> {
            CreateOrderReceiver receiver = new CreateOrderReceiver();
            receiver.setType(item.getType());
            receiver.setAccount(item.getAccount());
            receiver.setAmount(item.getAmount());
            receiver.setDescription(item.getDescription());
            return receiver;
        }).toList());

        return Result.success(service.createOrder(dto.getMerchantKey(), request));
    }

    /**
     * 查询微信分账单处理结果
     *
     * @param dto 分账单查询参数
     * @return 微信分账订单实体
     */
    @PostMapping("/orders/query")
    public Result<OrdersEntity> queryOrder(@Valid @RequestBody ProfitSharingOrderQueryDTO dto) {
        WechatProfitSharingService service = requireWechatService();
        log.info("Querying WeChat profit sharing order: outOrderNo={}, transactionId={}",
                dto.getOutOrderNo(), dto.getTransactionId());

        QueryOrderRequest request = new QueryOrderRequest();
        request.setSubMchid(dto.getSubMchid());
        request.setOutOrderNo(dto.getOutOrderNo());
        request.setTransactionId(dto.getTransactionId());

        return Result.success(service.queryOrder(dto.getMerchantKey(), request));
    }

    /**
     * 完结微信分账并解冻剩余在途资金
     *
     * @param dto 完结解冻请求参数
     * @return 微信分账订单实体
     */
    @PostMapping("/unfreeze")
    public Result<OrdersEntity> unfreezeOrder(@Valid @RequestBody ProfitSharingUnfreezeDTO dto) {
        WechatProfitSharingService service = requireWechatService();
        log.info("Unfreezing WeChat profit sharing order: outOrderNo={}, transactionId={}",
                dto.getOutOrderNo(), dto.getTransactionId());

        UnfreezeOrderRequest request = new UnfreezeOrderRequest();
        request.setOutOrderNo(dto.getOutOrderNo());
        request.setTransactionId(dto.getTransactionId());
        request.setDescription(dto.getDescription());

        return Result.success(service.unfreezeOrder(dto.getMerchantKey(), request));
    }

    /**
     * 发起微信分账回退请求
     *
     * @param dto 分账回退请求参数
     * @return 微信分账回退订单实体
     */
    @PostMapping("/return-orders/create")
    public Result<ReturnOrdersEntity> createReturnOrder(@Valid @RequestBody ProfitSharingReturnOrderDTO dto) {
        WechatProfitSharingService service = requireWechatService();
        log.info("Creating WeChat profit sharing return order: outReturnNo={}, outOrderNo={}",
                dto.getOutReturnNo(), dto.getOutOrderNo());

        CreateReturnOrderRequest request = new CreateReturnOrderRequest();
        request.setSubMchid(dto.getSubMchid());
        request.setOutOrderNo(dto.getOutOrderNo());
        request.setOutReturnNo(dto.getOutReturnNo());
        request.setReturnMchid(dto.getReturnMchid());
        request.setAmount(dto.getAmount());
        request.setDescription(dto.getDescription());

        return Result.success(service.createReturnOrder(dto.getMerchantKey(), request));
    }

    /**
     * 查询微信分账回退结果
     *
     * @param dto 分账回退查询参数
     * @return 微信分账回退订单实体
     */
    @PostMapping("/return-orders/query")
    public Result<ReturnOrdersEntity> queryReturnOrder(@Valid @RequestBody ProfitSharingReturnQueryDTO dto) {
        WechatProfitSharingService service = requireWechatService();
        log.info("Querying WeChat profit sharing return order: outReturnNo={}, outOrderNo={}",
                dto.getOutReturnNo(), dto.getOutOrderNo());

        QueryReturnOrderRequest request = new QueryReturnOrderRequest();
        request.setSubMchid(dto.getSubMchid());
        request.setOutReturnNo(dto.getOutReturnNo());
        request.setOutOrderNo(dto.getOutOrderNo());

        return Result.success(service.queryReturnOrder(dto.getMerchantKey(), request));
    }

    /**
     * 查询微信支付订单剩余待分金额
     *
     * @param dto 待分金额查询参数
     * @return 剩余待分金额响应结果
     */
    @PostMapping("/amounts/query")
    public Result<QueryOrderAmountResponse> queryOrderAmount(@Valid @RequestBody ProfitSharingAmountQueryDTO dto) {
        WechatProfitSharingService service = requireWechatService();
        log.info("Querying WeChat profit sharing remaining amount: transactionId={}", dto.getTransactionId());

        QueryOrderAmountRequest request = new QueryOrderAmountRequest();
        request.setTransactionId(dto.getTransactionId());

        return Result.success(service.queryOrderAmount(dto.getMerchantKey(), request));
    }

    /**
     * 查询微信特约子商户最大分账比例
     *
     * @param dto 最大分账比例查询参数
     * @return 最大分账比例响应结果
     */
    @PostMapping("/merchant-ratios/query")
    public Result<QueryMerchantRatioResponse> queryMerchantRatio(
            @Valid @RequestBody ProfitSharingMerchantRatioQueryDTO dto) {
        WechatProfitSharingService service = requireWechatService();
        log.info("Querying WeChat profit sharing merchant ratio: subMchid={}", dto.getSubMchid());

        QueryMerchantRatioRequest request = new QueryMerchantRatioRequest();
        request.setSubMchid(dto.getSubMchid());

        return Result.success(service.queryMerchantRatio(dto.getMerchantKey(), request));
    }

    // ==================== 2. 支付宝前置冻结与交易分账全链路 ====================

    /**
     * 支付宝前置分账冻结扫码预下单（通过 extend_params.royalty_freeze=true 冻结交易资金）
     *
     * @param dto 支付宝前置分账冻结收单请求参数
     * @return 统一收单响应（含 qrCode）
     */
    @PostMapping("/alipay/pre-freeze-qr")
    public Result<PayResponse> alipayPreFreezeQr(@Valid @RequestBody AlipayPreFreezeCheckoutDTO dto) {
        BusinessAssert.isTrue(alipayTemplate != null, "支付宝支付模块未启用");
        log.info("Creating Alipay pre-freeze QR checkout: outTradeNo={}, royaltyFreeze={}",
                dto.getOutTradeNo(), dto.getRoyaltyFreeze());

        AlipayPayRequest request = AlipayPayRequest.builder()
                .appKey(dto.getMerchantKey())
                .appAuthToken(dto.getAppAuthToken())
                .outTradeNo(dto.getOutTradeNo())
                .amount(dto.getAmount())
                .subject(dto.getSubject())
                .royaltyFreeze(dto.getRoyaltyFreeze())
                .build();

        return Result.success(alipayTemplate.payQr(request));
    }

    /**
     * 绑定支付宝分账接收方关系
     *
     * @param dto 分账关系绑定请求参数
     * @return 绑定结果响应
     */
    @PostMapping("/alipay/receivers/bind")
    public Result<AlipayTradeRoyaltyRelationBindResponse> bindAlipayReceivers(
            @Valid @RequestBody AlipayRoyaltyRelationDTO dto) {
        AlipayProfitSharingService service = requireAlipayService();
        log.info("Binding Alipay royalty relation: outRequestNo={}, count={}",
                dto.getOutRequestNo(), dto.getReceivers().size());

        AlipayTradeRoyaltyRelationBindModel model = new AlipayTradeRoyaltyRelationBindModel();
        model.setOutRequestNo(dto.getOutRequestNo());
        model.setReceiverList(toAlipayRoyaltyEntities(dto.getReceivers()));

        return Result.success(service.bindRelation(dto.getMerchantKey(), dto.getAppAuthToken(), model));
    }

    /**
     * 解绑支付宝分账接收方关系
     *
     * @param dto 分账关系解绑请求参数
     * @return 解绑结果响应
     */
    @PostMapping("/alipay/receivers/unbind")
    public Result<AlipayTradeRoyaltyRelationUnbindResponse> unbindAlipayReceivers(
            @Valid @RequestBody AlipayRoyaltyRelationDTO dto) {
        AlipayProfitSharingService service = requireAlipayService();
        log.info("Unbinding Alipay royalty relation: outRequestNo={}, count={}",
                dto.getOutRequestNo(), dto.getReceivers().size());

        AlipayTradeRoyaltyRelationUnbindModel model = new AlipayTradeRoyaltyRelationUnbindModel();
        model.setOutRequestNo(dto.getOutRequestNo());
        model.setReceiverList(toAlipayRoyaltyEntities(dto.getReceivers()));

        return Result.success(service.unbindRelation(dto.getMerchantKey(), dto.getAppAuthToken(), model));
    }

    /**
     * 分页批量查询支付宝分账接收方关系
     *
     * @param dto 分账关系分页查询参数
     * @return 分页查询结果响应
     */
    @PostMapping("/alipay/receivers/batchquery")
    public Result<AlipayTradeRoyaltyRelationBatchqueryResponse> batchQueryAlipayReceivers(
            @Valid @RequestBody AlipayRoyaltyRelationQueryDTO dto) {
        AlipayProfitSharingService service = requireAlipayService();
        log.info("Batch querying Alipay royalty relation: outRequestNo={}, pageNum={}",
                dto.getOutRequestNo(), dto.getPageNum());

        AlipayTradeRoyaltyRelationBatchqueryModel model = new AlipayTradeRoyaltyRelationBatchqueryModel();
        model.setOutRequestNo(dto.getOutRequestNo());
        model.setPageNum(dto.getPageNum());
        model.setPageSize(dto.getPageSize());

        return Result.success(service.batchQueryRelation(dto.getMerchantKey(), dto.getAppAuthToken(), model));
    }

    /**
     * 支付宝统一收单交易结算确认
     *
     * @param dto 交易结算确认参数
     * @return 结算确认结果响应
     */
    @PostMapping("/alipay/settle/confirm")
    public Result<AlipayTradeSettleConfirmResponse> confirmAlipaySettle(
            @Valid @RequestBody AlipaySettleConfirmDTO dto) {
        AlipayProfitSharingService service = requireAlipayService();
        log.info("Confirming Alipay trade settle: tradeNo={}, outRequestNo={}",
                dto.getTradeNo(), dto.getOutRequestNo());

        SettleDetailInfo detail = new SettleDetailInfo();
        detail.setTransInType("defaultSettle");
        detail.setAmount(dto.getAmount().setScale(2, RoundingMode.HALF_UP).toPlainString());

        SettleInfo settleInfo = new SettleInfo();
        settleInfo.setSettleDetailInfos(List.of(detail));
        settleInfo.setSettlePeriodTime(dto.getSettlePeriodTime());

        AlipayTradeSettleConfirmModel model = new AlipayTradeSettleConfirmModel();
        model.setOutRequestNo(dto.getOutRequestNo());
        model.setTradeNo(dto.getTradeNo());
        model.setSettleInfo(settleInfo);

        return Result.success(service.confirmSettle(dto.getMerchantKey(), dto.getAppAuthToken(), model));
    }

    /**
     * 支付宝统一收单交易分账结算
     *
     * @param dto 订单分账结算请求参数
     * @return 分账结算响应
     */
    @PostMapping("/alipay/settle/order")
    public Result<AlipayTradeOrderSettleResponse> settleAlipayOrder(@Valid @RequestBody AlipayOrderSettleDTO dto) {
        AlipayProfitSharingService service = requireAlipayService();
        log.info("Settling Alipay trade order: tradeNo={}, outRequestNo={}",
                dto.getTradeNo(), dto.getOutRequestNo());

        AlipayTradeOrderSettleModel model = new AlipayTradeOrderSettleModel();
        model.setOutRequestNo(dto.getOutRequestNo());
        model.setTradeNo(dto.getTradeNo());
        model.setRoyaltyParameters(dto.getRoyaltyParameters().stream().map(item -> {
            OpenApiRoyaltyDetailInfoPojo pojo = new OpenApiRoyaltyDetailInfoPojo();
            pojo.setTransInType(item.getTransInType());
            pojo.setTransIn(item.getTransIn());
            pojo.setAmount(item.getAmount().setScale(2, RoundingMode.HALF_UP).toPlainString());
            pojo.setDesc(item.getDesc());
            pojo.setRoyaltyType(item.getRoyaltyType());
            return pojo;
        }).toList());

        return Result.success(service.orderSettle(dto.getMerchantKey(), dto.getAppAuthToken(), model));
    }

    /**
     * 查询支付宝统一收单分账进度
     *
     * @param dto 分账结算进度查询参数
     * @return 分账详情列表查询响应
     */
    @PostMapping("/alipay/settle/query")
    public Result<AlipayTradeOrderSettleQueryResponse> queryAlipaySettle(
            @Valid @RequestBody AlipayOrderSettleQueryDTO dto) {
        AlipayProfitSharingService service = requireAlipayService();
        log.info("Querying Alipay trade settle: settleNo={}, tradeNo={}", dto.getSettleNo(), dto.getTradeNo());

        AlipayTradeOrderSettleQueryModel model = new AlipayTradeOrderSettleQueryModel();
        model.setSettleNo(dto.getSettleNo());
        model.setOutRequestNo(dto.getOutRequestNo());
        model.setTradeNo(dto.getTradeNo());

        return Result.success(service.queryOrderSettle(dto.getMerchantKey(), dto.getAppAuthToken(), model));
    }

    private WechatProfitSharingService requireWechatService() {
        BusinessAssert.isTrue(profitSharingService != null, "微信资金分账特性未启用");
        return profitSharingService;
    }

    private AlipayProfitSharingService requireAlipayService() {
        BusinessAssert.isTrue(alipayProfitSharingService != null, "支付宝交易分账特性未启用");
        return alipayProfitSharingService;
    }

    private <E extends Enum<E>> E parseEnum(Class<E> enumType, String rawValue, String fieldName) {
        try {
            return Enum.valueOf(enumType, rawValue.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException ex) {
            throw new BusinessException(CommonResultCode.BAD_REQUEST.getCode(),
                    "非法的分账枚举字段 [" + fieldName + "]: " + rawValue, ex);
        }
    }

    private List<RoyaltyEntity> toAlipayRoyaltyEntities(List<AlipayRoyaltyRelationDTO.ReceiverItem> items) {
        return items.stream().map(item -> {
            RoyaltyEntity entity = new RoyaltyEntity();
            entity.setType(item.getType());
            entity.setAccount(item.getAccount());
            entity.setName(item.getName());
            entity.setMemo(item.getMemo());
            return entity;
        }).toList();
    }
}
