package io.github.atengk.boot.sms.callback;

import io.github.atengk.boot.sms.model.SmsChannel;
import lombok.Builder;
import lombok.Getter;
import org.springframework.http.HttpHeaders;
import org.springframework.util.MultiValueMap;

/**
 * 入站 Webhook 回调安全校验上下文
 *
 * @author Ateng
 * @since 2026-11-21
 */
@Getter
@Builder
public class SmsCallbackVerificationContext {

    /**
     * 路径参数中的渠道名称
     */
    private final String channelName;

    /**
     * 注册中心解析得到的渠道配置（可为 null，如内置 mock）
     */
    private final SmsChannel channel;

    /**
     * 原始 HTTP 请求体文本
     */
    private final String rawBody;

    /**
     * 请求查询或表单参数集合
     */
    private final MultiValueMap<String, String> formParams;

    /**
     * 请求头集合
     */
    private final HttpHeaders headers;

    /**
     * 物理连接远端 IP（经端口清洗）
     */
    private final String remoteIp;

    /**
     * URL Query 参数中提取的 token（用于审计及 allowQueryToken 控制）
     */
    private final String queryToken;

    /**
     * URL Query String 中是否显式携带了 token 参数
     */
    private final boolean queryTokenPresent;
}
