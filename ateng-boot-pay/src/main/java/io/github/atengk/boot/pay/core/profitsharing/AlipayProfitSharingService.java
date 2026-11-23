package io.github.atengk.boot.pay.core.profitsharing;

import com.alipay.api.AlipayClient;
import com.alipay.api.domain.AlipayTradeOrderSettleModel;
import com.alipay.api.domain.AlipayTradeOrderSettleQueryModel;
import com.alipay.api.domain.AlipayTradeRoyaltyRelationBatchqueryModel;
import com.alipay.api.domain.AlipayTradeRoyaltyRelationBindModel;
import com.alipay.api.domain.AlipayTradeRoyaltyRelationUnbindModel;
import com.alipay.api.domain.AlipayTradeSettleConfirmModel;
import com.alipay.api.response.AlipayTradeOrderSettleQueryResponse;
import com.alipay.api.response.AlipayTradeOrderSettleResponse;
import com.alipay.api.response.AlipayTradeRoyaltyRelationBatchqueryResponse;
import com.alipay.api.response.AlipayTradeRoyaltyRelationBindResponse;
import com.alipay.api.response.AlipayTradeRoyaltyRelationUnbindResponse;
import com.alipay.api.response.AlipayTradeSettleConfirmResponse;
import io.github.atengk.boot.pay.core.AlipayTemplate;

import java.util.Objects;

/**
 * 支付宝交易分账与结算专有业务服务门面
 * <p>
 * 仅在 {@code ateng.boot.pay.profit-sharing.enabled=true} 时通过自动装配生效，与微信分账共享同一高敏资金分账开关守卫，
 * 封装支付宝分账关系绑定、解绑、批量查询、交易结算确认、统一订单分账结算与分账进度查询，全链支持 ISV 服务商 {@code app_auth_token} 透传。
 * </p>
 *
 * @author Ateng
 * @since 2026-11-18
 */
public class AlipayProfitSharingService {

    private final AlipayTemplate alipayTemplate;

    public AlipayProfitSharingService(AlipayTemplate alipayTemplate) {
        this.alipayTemplate = Objects.requireNonNull(alipayTemplate, "AlipayTemplate 不能为空");
    }

    /**
     * 分账关系绑定（默认应用）
     *
     * @param model 分账关系绑定请求模型
     * @return 绑定结果响应
     */
    public AlipayTradeRoyaltyRelationBindResponse bindRelation(AlipayTradeRoyaltyRelationBindModel model) {
        return alipayTemplate.bindRoyaltyRelation(model);
    }

    /**
     * 分账关系绑定（指定应用别名或 AppId）
     *
     * @param appKey 应用配置别名或 AppId（可选）
     * @param model  分账关系绑定请求模型
     * @return 绑定结果响应
     */
    public AlipayTradeRoyaltyRelationBindResponse bindRelation(String appKey, AlipayTradeRoyaltyRelationBindModel model) {
        return alipayTemplate.bindRoyaltyRelation(appKey, model);
    }

    /**
     * 分账关系绑定（指定应用及显式服务商授权令牌）
     *
     * @param appKey       应用配置别名或 AppId（可选）
     * @param appAuthToken 服务商第三方应用授权令牌（可选，留空自动回退应用配置）
     * @param model        分账关系绑定请求模型
     * @return 绑定结果响应
     */
    public AlipayTradeRoyaltyRelationBindResponse bindRelation(String appKey,
                                                             String appAuthToken,
                                                             AlipayTradeRoyaltyRelationBindModel model) {
        return alipayTemplate.bindRoyaltyRelation(appKey, appAuthToken, model);
    }

    /**
     * 分账关系解绑（默认应用）
     *
     * @param model 分账关系解绑请求模型
     * @return 解绑结果响应
     */
    public AlipayTradeRoyaltyRelationUnbindResponse unbindRelation(AlipayTradeRoyaltyRelationUnbindModel model) {
        return alipayTemplate.unbindRoyaltyRelation(model);
    }

    /**
     * 分账关系解绑（指定应用别名或 AppId）
     *
     * @param appKey 应用配置别名或 AppId（可选）
     * @param model  分账关系解绑请求模型
     * @return 解绑结果响应
     */
    public AlipayTradeRoyaltyRelationUnbindResponse unbindRelation(String appKey,
                                                                 AlipayTradeRoyaltyRelationUnbindModel model) {
        return alipayTemplate.unbindRoyaltyRelation(appKey, model);
    }

    /**
     * 分账关系解绑（指定应用及显式服务商授权令牌）
     *
     * @param appKey       应用配置别名或 AppId（可选）
     * @param appAuthToken 服务商第三方应用授权令牌（可选，留空自动回退应用配置）
     * @param model        分账关系解绑请求模型
     * @return 解绑结果响应
     */
    public AlipayTradeRoyaltyRelationUnbindResponse unbindRelation(String appKey,
                                                                 String appAuthToken,
                                                                 AlipayTradeRoyaltyRelationUnbindModel model) {
        return alipayTemplate.unbindRoyaltyRelation(appKey, appAuthToken, model);
    }

    /**
     * 分账关系分页批量查询（默认应用）
     *
     * @param model 分账关系批量查询请求模型
     * @return 分页查询结果响应
     */
    public AlipayTradeRoyaltyRelationBatchqueryResponse batchQueryRelation(AlipayTradeRoyaltyRelationBatchqueryModel model) {
        return alipayTemplate.batchQueryRoyaltyRelation(model);
    }

    /**
     * 分账关系分页批量查询（指定应用别名或 AppId）
     *
     * @param appKey 应用配置别名或 AppId（可选）
     * @param model  分账关系批量查询请求模型
     * @return 分页查询结果响应
     */
    public AlipayTradeRoyaltyRelationBatchqueryResponse batchQueryRelation(String appKey,
                                                                           AlipayTradeRoyaltyRelationBatchqueryModel model) {
        return alipayTemplate.batchQueryRoyaltyRelation(appKey, model);
    }

    /**
     * 分账关系分页批量查询（指定应用及显式服务商授权令牌）
     *
     * @param appKey       应用配置别名或 AppId（可选）
     * @param appAuthToken 服务商第三方应用授权令牌（可选，留空自动回退应用配置）
     * @param model        分账关系批量查询请求模型
     * @return 分页查询结果响应
     */
    public AlipayTradeRoyaltyRelationBatchqueryResponse batchQueryRelation(String appKey,
                                                                           String appAuthToken,
                                                                           AlipayTradeRoyaltyRelationBatchqueryModel model) {
        return alipayTemplate.batchQueryRoyaltyRelation(appKey, appAuthToken, model);
    }

    /**
     * 统一收单交易结算确认（默认应用）
     *
     * @param model 结算确认请求模型
     * @return 结算确认结果响应
     */
    public AlipayTradeSettleConfirmResponse confirmSettle(AlipayTradeSettleConfirmModel model) {
        return alipayTemplate.confirmSettle(model);
    }

    /**
     * 统一收单交易结算确认（指定应用别名或 AppId）
     *
     * @param appKey 应用配置别名或 AppId（可选）
     * @param model  结算确认请求模型
     * @return 结算确认结果响应
     */
    public AlipayTradeSettleConfirmResponse confirmSettle(String appKey, AlipayTradeSettleConfirmModel model) {
        return alipayTemplate.confirmSettle(appKey, model);
    }

    /**
     * 统一收单交易结算确认（指定应用及显式服务商授权令牌）
     *
     * @param appKey       应用配置别名或 AppId（可选）
     * @param appAuthToken 服务商第三方应用授权令牌（可选，留空自动回退应用配置）
     * @param model        结算确认请求模型
     * @return 结算确认结果响应
     */
    public AlipayTradeSettleConfirmResponse confirmSettle(String appKey,
                                                          String appAuthToken,
                                                          AlipayTradeSettleConfirmModel model) {
        return alipayTemplate.confirmSettle(appKey, appAuthToken, model);
    }

    /**
     * 统一收单交易分账结算（默认应用）
     *
     * @param model 订单分账结算请求模型
     * @return 分账结算响应
     */
    public AlipayTradeOrderSettleResponse orderSettle(AlipayTradeOrderSettleModel model) {
        return alipayTemplate.settleOrder(model);
    }

    /**
     * 统一收单交易分账结算（指定应用别名或 AppId）
     *
     * @param appKey 应用配置别名或 AppId（可选）
     * @param model  订单分账结算请求模型
     * @return 分账结算响应
     */
    public AlipayTradeOrderSettleResponse orderSettle(String appKey, AlipayTradeOrderSettleModel model) {
        return alipayTemplate.settleOrder(appKey, model);
    }

    /**
     * 统一收单交易分账结算（指定应用及显式服务商授权令牌）
     *
     * @param appKey       应用配置别名或 AppId（可选）
     * @param appAuthToken 服务商第三方应用授权令牌（可选，留空自动回退应用配置）
     * @param model        订单分账结算请求模型
     * @return 分账结算响应
     */
    public AlipayTradeOrderSettleResponse orderSettle(String appKey,
                                                      String appAuthToken,
                                                      AlipayTradeOrderSettleModel model) {
        return alipayTemplate.settleOrder(appKey, appAuthToken, model);
    }

    /**
     * 交易分账进度查询（默认应用）
     *
     * @param model 分账进度查询请求模型
     * @return 分账详情列表查询响应
     */
    public AlipayTradeOrderSettleQueryResponse queryOrderSettle(AlipayTradeOrderSettleQueryModel model) {
        return alipayTemplate.querySettleOrder(model);
    }

    /**
     * 交易分账进度查询（指定应用别名或 AppId）
     *
     * @param appKey 应用配置别名或 AppId（可选）
     * @param model  分账进度查询请求模型
     * @return 分账详情列表查询响应
     */
    public AlipayTradeOrderSettleQueryResponse queryOrderSettle(String appKey, AlipayTradeOrderSettleQueryModel model) {
        return alipayTemplate.querySettleOrder(appKey, model);
    }

    /**
     * 交易分账进度查询（指定应用及显式服务商授权令牌）
     *
     * @param appKey       应用配置别名或 AppId（可选）
     * @param appAuthToken 服务商第三方应用授权令牌（可选，留空自动回退应用配置）
     * @param model        分账进度查询请求模型
     * @return 分账详情列表查询响应
     */
    public AlipayTradeOrderSettleQueryResponse queryOrderSettle(String appKey,
                                                                String appAuthToken,
                                                                AlipayTradeOrderSettleQueryModel model) {
        return alipayTemplate.querySettleOrder(appKey, appAuthToken, model);
    }

    /**
     * 获取默认应用原生 {@link AlipayClient} 逃生舱
     *
     * @return 官方 SDK 原生 AlipayClient 实例
     */
    public AlipayClient getAlipayClient() {
        return alipayTemplate.getAlipayClient();
    }

    /**
     * 获取指定应用原生 {@link AlipayClient} 逃生舱
     *
     * @param appKey 应用配置别名或 AppId
     * @return 官方 SDK 原生 AlipayClient 实例
     */
    public AlipayClient getAlipayClient(String appKey) {
        return alipayTemplate.getAlipayClient(appKey);
    }
}
