package io.github.atengk.boot.ai.streaming.event;

import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.model.output.Response;
import dev.langchain4j.rag.content.Content;
import dev.langchain4j.service.tool.ToolExecution;

import java.util.List;

/**
 * 智能体过程流式事件监听器 SPI
 * <p>
 * 解耦智能体执行生命周期中的流式事件监听与外部展示传输层 (SSE / WebSocket / 日志 / 审计追踪)。
 * 凡需感知智能体思考、工具调用、工具返回、RAG 检索与终态完成者，均可实现本接口接入。
 * </p>
 *
 * @author Ateng
 * @since 2026-11-06
 */
public interface AgentStreamListener {

    /**
     * 接收到通用流式事件
     *
     * @param event 流式事件对象
     */
    default void onEvent(AgentStreamEvent event) {}

    /**
     * 接收到思考链推理 Token 片段
     *
     * @param token 思考内容片段
     */
    default void onReasoning(String token) {
        onEvent(AgentStreamEvent.reasoning(token));
    }

    /**
     * 接收到回答正文打字机 Token 片段
     *
     * @param token 正文内容片段
     */
    default void onText(String token) {
        onEvent(AgentStreamEvent.text(token));
    }

    /**
     * 检索到外部 RAG 候选切片内容
     *
     * @param contents 召回切片列表
     */
    default void onRetrieved(List<Content> contents) {
        onEvent(AgentStreamEvent.ragRetrieved(contents));
    }

    /**
     * 工具执行完成事件 (包含调用的工具名称、参数快照与执行结果)
     *
     * @param toolExecution 工具执行请求与结果快照
     */
    default void onToolExecuted(ToolExecution toolExecution) {
        if (toolExecution != null && toolExecution.request() != null) {
            onEvent(AgentStreamEvent.toolCall(
                    toolExecution.request().id(),
                    toolExecution.request().name(),
                    toolExecution.request().arguments()
            ));
            onEvent(AgentStreamEvent.toolResult(
                    toolExecution.request().id(),
                    toolExecution.request().name(),
                    toolExecution.result()
            ));
        }
    }

    /**
     * 智能体流式对话正常完成
     *
     * @param response 完整响应元数据
     */
    default void onComplete(Response<AiMessage> response) {
        onEvent(AgentStreamEvent.done());
    }

    /**
     * 智能体流式执行发生异常终止
     *
     * @param error 异常对象
     */
    default void onError(Throwable error) {
        onEvent(AgentStreamEvent.error(error != null ? error.getMessage() : "Unknown error"));
    }
}
