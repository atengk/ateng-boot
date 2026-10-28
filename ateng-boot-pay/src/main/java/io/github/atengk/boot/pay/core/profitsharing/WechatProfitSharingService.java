package io.github.atengk.boot.pay.core.profitsharing;

import com.wechat.pay.java.service.profitsharing.ProfitsharingService;
import com.wechat.pay.java.service.profitsharing.model.*;
import io.github.atengk.boot.pay.core.WechatPayTemplate;

import java.util.Objects;

/**
 * 微信支付资金分账专有业务服务门面
 * <p>
 * 仅在 {@code ateng.boot.pay.profit-sharing.enabled=true} 时通过自动装配生效，
 * 封装微信支付 V3 分账全生命周期（添加/删除接收方、发起分账、查询分账、完结解冻资金、分账回退）。
 * </p>
 *
 * @author Ateng
 * @since 2026-10-24
 */
public class WechatProfitSharingService {

    private final WechatPayTemplate wechatPayTemplate;

    public WechatProfitSharingService(WechatPayTemplate wechatPayTemplate) {
        this.wechatPayTemplate = Objects.requireNonNull(wechatPayTemplate, "WechatPayTemplate 不能为空");
    }

    /**
     * 添加分账接收方
     *
     * @param merchantKey 商户配置键或商户号（可选）
     * @param request     添加接收方请求入参
     * @return 添加分账接收方响应实体
     */
    public AddReceiverResponse addReceiver(String merchantKey, AddReceiverRequest request) {
        return wechatPayTemplate.addProfitSharingReceiver(merchantKey, request);
    }

    /**
     * 添加分账接收方（默认商户）
     *
     * @param request 添加接收方请求入参
     * @return 添加分账接收方响应实体
     */
    public AddReceiverResponse addReceiver(AddReceiverRequest request) {
        return wechatPayTemplate.addProfitSharingReceiver(request);
    }

    /**
     * 删除分账接收方
     *
     * @param merchantKey 商户配置键或商户号（可选）
     * @param request     删除接收方请求入参
     * @return 删除分账接收方响应实体
     */
    public DeleteReceiverResponse deleteReceiver(String merchantKey, DeleteReceiverRequest request) {
        return wechatPayTemplate.deleteProfitSharingReceiver(merchantKey, request);
    }

    /**
     * 删除分账接收方（默认商户）
     *
     * @param request 删除接收方请求入参
     * @return 删除分账接收方响应实体
     */
    public DeleteReceiverResponse deleteReceiver(DeleteReceiverRequest request) {
        return wechatPayTemplate.deleteProfitSharingReceiver(request);
    }

    /**
     * 请求分账
     *
     * @param merchantKey 商户配置键或商户号（可选）
     * @param request     请求分账入参
     * @return 微信分账订单实体
     */
    public OrdersEntity createOrder(String merchantKey, CreateOrderRequest request) {
        return wechatPayTemplate.createProfitSharing(merchantKey, request);
    }

    /**
     * 请求分账（默认商户）
     *
     * @param request 请求分账入参
     * @return 微信分账订单实体
     */
    public OrdersEntity createOrder(CreateOrderRequest request) {
        return wechatPayTemplate.createProfitSharing(request);
    }

    /**
     * 查询分账结果
     *
     * @param merchantKey 商户配置键或商户号（可选）
     * @param request     查询分账结果入参
     * @return 微信分账订单实体
     */
    public OrdersEntity queryOrder(String merchantKey, QueryOrderRequest request) {
        return wechatPayTemplate.queryProfitSharing(merchantKey, request);
    }

    /**
     * 查询分账结果（默认商户）
     *
     * @param request 查询分账结果入参
     * @return 微信分账订单实体
     */
    public OrdersEntity queryOrder(QueryOrderRequest request) {
        return wechatPayTemplate.queryProfitSharing(request);
    }

    /**
     * 便捷重载：查询分账结果（默认商户）
     *
     * @param outOrderNo    商户分账单号
     * @param transactionId 微信支付订单号
     * @return 微信分账订单实体
     */
    public OrdersEntity queryOrder(String outOrderNo, String transactionId) {
        return wechatPayTemplate.queryProfitSharing(outOrderNo, transactionId);
    }

    /**
     * 便捷重载：查询指定商户的分账结果
     *
     * @param merchantKey   商户别名或商户号
     * @param outOrderNo    商户分账单号
     * @param transactionId 微信支付订单号
     * @return 微信分账订单实体
     */
    public OrdersEntity queryOrder(String merchantKey, String outOrderNo, String transactionId) {
        return wechatPayTemplate.queryProfitSharing(merchantKey, outOrderNo, transactionId);
    }

    /**
     * 完结分账并解冻订单剩余在途资金 (unfreeze)
     *
     * @param merchantKey 商户配置键或商户号（可选）
     * @param request     解冻请求入参
     * @return 微信分账订单实体
     */
    public OrdersEntity unfreezeOrder(String merchantKey, UnfreezeOrderRequest request) {
        return wechatPayTemplate.unfreezeProfitSharing(merchantKey, request);
    }

    /**
     * 完结分账并解冻订单剩余在途资金（默认商户）
     *
     * @param request 解冻请求入参
     * @return 微信分账订单实体
     */
    public OrdersEntity unfreezeOrder(UnfreezeOrderRequest request) {
        return wechatPayTemplate.unfreezeProfitSharing(request);
    }

    /**
     * 便捷重载：完结解冻分账资金（默认商户）
     *
     * @param outOrderNo    商户分账单号
     * @param transactionId 微信支付订单号
     * @param description   解冻描述
     * @return 微信分账订单实体
     */
    public OrdersEntity unfreezeOrder(String outOrderNo, String transactionId, String description) {
        return wechatPayTemplate.unfreezeProfitSharing(outOrderNo, transactionId, description);
    }

    /**
     * 便捷重载：完结解冻指定商户的分账资金
     *
     * @param merchantKey   商户别名或商户号
     * @param outOrderNo    商户分账单号
     * @param transactionId 微信支付订单号
     * @param description   解冻描述
     * @return 微信分账订单实体
     */
    public OrdersEntity unfreezeOrder(String merchantKey, String outOrderNo, String transactionId, String description) {
        return wechatPayTemplate.unfreezeProfitSharing(merchantKey, outOrderNo, transactionId, description);
    }

    /**
     * 请求分账回退
     *
     * @param merchantKey 商户配置键或商户号（可选）
     * @param request     分账回退请求入参
     * @return 分账回退订单实体
     */
    public ReturnOrdersEntity createReturnOrder(String merchantKey, CreateReturnOrderRequest request) {
        return wechatPayTemplate.returnProfitSharing(merchantKey, request);
    }

    /**
     * 请求分账回退（默认商户）
     *
     * @param request 分账回退请求入参
     * @return 分账回退订单实体
     */
    public ReturnOrdersEntity createReturnOrder(CreateReturnOrderRequest request) {
        return wechatPayTemplate.returnProfitSharing(request);
    }

    /**
     * 查询分账回退结果
     *
     * @param merchantKey 商户配置键或商户号（可选）
     * @param request     查询分账回退结果请求入参
     * @return 分账回退订单实体
     */
    public ReturnOrdersEntity queryReturnOrder(String merchantKey, QueryReturnOrderRequest request) {
        return wechatPayTemplate.queryProfitSharingReturn(merchantKey, request);
    }

    /**
     * 查询分账回退结果（默认商户）
     *
     * @param request 查询分账回退结果请求入参
     * @return 分账回退订单实体
     */
    public ReturnOrdersEntity queryReturnOrder(QueryReturnOrderRequest request) {
        return wechatPayTemplate.queryProfitSharingReturn(request);
    }

    /**
     * 便捷重载：查询分账回退结果（默认商户）
     *
     * @param outReturnNo 商户回退单号
     * @param outOrderNo  商户分账单号
     * @return 分账回退订单实体
     */
    public ReturnOrdersEntity queryReturnOrder(String outReturnNo, String outOrderNo) {
        return wechatPayTemplate.queryProfitSharingReturn(outReturnNo, outOrderNo);
    }

    /**
     * 便捷重载：查询指定商户的分账回退结果
     *
     * @param merchantKey 商户别名或商户号
     * @param outReturnNo 商户回退单号
     * @param outOrderNo  商户分账单号
     * @return 分账回退订单实体
     */
    public ReturnOrdersEntity queryReturnOrder(String merchantKey, String outReturnNo, String outOrderNo) {
        return wechatPayTemplate.queryProfitSharingReturn(merchantKey, outReturnNo, outOrderNo);
    }

    /**
     * 查询剩余待分金额
     *
     * @param merchantKey 商户配置键或商户号（可选）
     * @param request     查询待分金额请求
     * @return 待分金额响应实体
     */
    public QueryOrderAmountResponse queryOrderAmount(String merchantKey, QueryOrderAmountRequest request) {
        return wechatPayTemplate.queryProfitSharingAmount(merchantKey, request);
    }

    /**
     * 查询剩余待分金额（默认商户）
     *
     * @param request 查询待分金额请求
     * @return 待分金额响应实体
     */
    public QueryOrderAmountResponse queryOrderAmount(QueryOrderAmountRequest request) {
        return wechatPayTemplate.queryProfitSharingAmount(request);
    }

    /**
     * 查询最大分账比例
     *
     * @param merchantKey 商户配置键或商户号（可选）
     * @param request     查询最大分账比例请求
     * @return 最大分账比例响应实体
     */
    public QueryMerchantRatioResponse queryMerchantRatio(String merchantKey, QueryMerchantRatioRequest request) {
        return wechatPayTemplate.queryProfitSharingMerchantRatio(merchantKey, request);
    }

    /**
     * 查询最大分账比例（默认商户）
     *
     * @param request 查询最大分账比例请求
     * @return 最大分账比例响应实体
     */
    public QueryMerchantRatioResponse queryMerchantRatio(QueryMerchantRatioRequest request) {
        return wechatPayTemplate.queryProfitSharingMerchantRatio(request);
    }

    /**
     * 获取指定商户的原生 ProfitsharingService 逃生舱
     *
     * @param merchantKey 商户配置键或商户号
     * @return 官方 SDK 原生 ProfitsharingService 实例
     */
    public ProfitsharingService getProfitsharingService(String merchantKey) {
        return wechatPayTemplate.getProfitsharingService(merchantKey);
    }

    /**
     * 获取默认商户的原生 ProfitsharingService 逃生舱
     *
     * @return 官方 SDK 原生 ProfitsharingService 实例
     */
    public ProfitsharingService getProfitsharingService() {
        return wechatPayTemplate.getProfitsharingService();
    }
}
