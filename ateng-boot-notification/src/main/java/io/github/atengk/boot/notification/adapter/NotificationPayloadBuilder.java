package io.github.atengk.boot.notification.adapter;

import io.github.atengk.boot.core.util.JsonUtil;
import io.github.atengk.boot.notification.model.NotificationMessage;
import io.github.atengk.boot.notification.model.RawMessage;

import java.util.Map;

/**
 * 协同平台 Webhook 请求体构建适配器接口
 *
 * @author Ateng
 * @since 2026-10-23
 */
public interface NotificationPayloadBuilder {

    /**
     * 将统一领域消息转译为目标协同平台专属的 Map 报文结构
     *
     * @param message 领域通知消息
     * @return 目标平台专属的 JSON 映射结构
     */
    Map<String, Object> buildPayload(NotificationMessage message);

    /**
     * 将统一领域消息转译为目标协同平台专属的 JSON 字符串
     *
     * @param message 领域通知消息
     * @return 目标平台专属的 JSON 字符串
     */
    default String buildJsonPayload(NotificationMessage message) {
        Map<String, Object> payload = buildPayload(message);
        return JsonUtil.toJsonString(payload);
    }

    /**
     * 将原生逃生舱消息提取合并至目标平台根报文
     *
     * @param root 平台根报文容器
     * @param msg  原生消息实例
     */
    default void buildRawPayload(Map<String, Object> root, RawMessage msg) {
        if (root != null && msg != null) {
            root.putAll(msg.toPayloadMap());
        }
    }
}
