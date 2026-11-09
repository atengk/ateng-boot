package io.github.atengk.boot.sample.realtime.controller;

import io.github.atengk.boot.realtime.sse.SseStreamHelper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;

/**
 * Server-Sent Events (SSE) 流式推流与大模型打字机控制器
 *
 * @author Ateng
 * @since 2026-11-09
 */
@Slf4j
@RestController
@RequestMapping("/sample/realtime/sse")
@RequiredArgsConstructor
public class RealtimeSseStreamController {

    private final ObjectProvider<Executor> taskExecutorProvider;

    /**
     * 演示 LLM 风格打字机异步流式推流（基于 SseStreamHelper 优雅分块与客户端中止静默拦截）
     */
    @GetMapping(value = "/stream-llm", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter streamLlm(@RequestParam(defaultValue = "Explain realtime architecture") String prompt) {
        log.info("[Sample] 触发 LLM 打字机流式推流: prompt={}", prompt);
        SseEmitter emitter = SseStreamHelper.create(60_000L);
        Runnable task = () -> {
            try {
                String[] words = {"[Prompt: ", prompt, "] ", "Hello", " ", "World", "!", " ", "This", " ", "is", " ", "ateng-boot-realtime", " ", "streaming."};
                for (String word : words) {
                    Thread.sleep(50);
                    // 发送分块；若客户端主动断开连接，sendChunk 内部静默拦截并立即返回 false，避免下游无谓消耗推理算力
                    boolean sent = SseStreamHelper.sendChunk(emitter, word);
                    if (!sent) {
                        log.debug("[Sample] 客户端已断开打字机推流，终止任务");
                        return;
                    }
                }
                SseStreamHelper.complete(emitter);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        };

        Executor executor = taskExecutorProvider.getIfAvailable();
        if (executor != null) {
            executor.execute(task);
        } else {
            CompletableFuture.runAsync(task);
        }
        return emitter;
    }
}
