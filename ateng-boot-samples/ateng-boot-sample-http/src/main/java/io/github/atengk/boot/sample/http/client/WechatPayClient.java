package io.github.atengk.boot.sample.http.client;

import io.github.atengk.boot.http.annotation.HttpClient;
import io.github.atengk.boot.sample.http.dto.WechatNativeOrderRequest;
import io.github.atengk.boot.sample.http.dto.WechatNativeOrderResponse;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.service.annotation.HttpExchange;
import org.springframework.web.service.annotation.PostExchange;

/**
 * 外部公网 OpenAPI 声明式 HTTP 客户端接口
 * 演示对接外部非信任第三方（如微信支付），自动执行租户与用户等内网私有敏感头安全剥离
 *
 * @author Ateng
 * @since 2026-10-21
 */
@HttpClient(name = "wechat-pay", url = "${sample.wechat-pay.url:https://api.mch.weixin.qq.com}")
@HttpExchange
public interface WechatPayClient {

    /**
     * 创建 Native 统一下单预支付交易单（强类型 DTO/VO 契约）
     *
     * @param request 下单请求载荷
     * @return 预支付响应载荷（包含 code_url 等）
     */
    @PostExchange("/v3/pay/transactions/native")
    WechatNativeOrderResponse createNativeOrder(@RequestBody WechatNativeOrderRequest request);
}
