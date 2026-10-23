package io.github.atengk.boot.notification.template;

import io.github.atengk.boot.notification.model.NotificationMessage;
import io.github.atengk.boot.notification.model.NotificationSendResult;

import java.util.Map;
import java.util.concurrent.CompletableFuture;

/**
 * 协同通知操作核心门面接口契约
 * 规范同步发送、异步四维快照透传发送、事务感知发信与模板化动态渲染
 *
 * @author Ateng
 * @since 2026-10-23
 */
public interface NotificationOperations {

    /**
     * 同步发送通知（投递至默认或上下文渠道）
     *
     * @param message 待推送的通知领域消息
     * @return 发送结果
     */
    NotificationSendResult send(NotificationMessage message);

    /**
     * 同步发送通知（指定目标渠道）
     *
     * @param channelName 目标渠道名称
     * @param message     待推送的通知领域消息
     * @return 发送结果
     */
    NotificationSendResult send(String channelName, NotificationMessage message);

    /**
     * 异步非阻塞发送通知（投递至默认或上下文渠道）
     * 调度 Core 受管线程池并由 AsyncContext 穿透四维全链路上下文快照
     *
     * @param message 待推送的通知领域消息
     * @return 包含发送结果的 CompletableFuture
     */
    CompletableFuture<NotificationSendResult> sendAsync(NotificationMessage message);

    /**
     * 异步非阻塞发送通知（指定目标渠道）
     * 调度 Core 受管线程池并由 AsyncContext 穿透四维全链路上下文快照
     *
     * @param channelName 目标渠道名称
     * @param message     待推送的通知领域消息
     * @return 包含发送结果的 CompletableFuture
     */
    CompletableFuture<NotificationSendResult> sendAsync(String channelName, NotificationMessage message);

    /**
     * 本地事务感知发送通知（投递至默认或上下文渠道）
     * 在数据库物理事务提交后触发，发送失败自动转入失败补偿处理器
     *
     * @param message 待推送的通知领域消息
     */
    void sendAfterCommit(NotificationMessage message);

    /**
     * 本地事务感知发送通知（指定目标渠道）
     * 在数据库物理事务提交后触发，发送失败自动转入失败补偿处理器
     *
     * @param channelName 目标渠道名称
     * @param message     待推送的通知领域消息
     */
    void sendAfterCommit(String channelName, NotificationMessage message);

    /**
     * 模板化动态渲染并发送（投递至默认或上下文渠道）
     *
     * @param templateContent 模板正文（支持 ${var} 占位符）
     * @param variables       模板变量上下文
     * @return 发送结果
     */
    NotificationSendResult sendTemplate(String templateContent, Map<String, Object> variables);

    /**
     * 模板化动态渲染并发送（指定目标渠道）
     *
     * @param channelName     目标渠道名称
     * @param templateContent 模板正文（支持 ${var} 占位符）
     * @param variables       模板变量上下文
     * @return 发送结果
     */
    NotificationSendResult sendTemplate(String channelName, String templateContent, Map<String, Object> variables);

    /**
     * 模板化动态渲染领域消息并发送（指定目标渠道）
     *
     * @param channelName     目标渠道名称
     * @param messageTemplate 包含占位符的消息模板领域实体
     * @param variables       模板变量上下文
     * @return 发送结果
     */
    NotificationSendResult sendTemplate(String channelName, NotificationMessage messageTemplate, Map<String, Object> variables);
}
