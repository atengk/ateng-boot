package io.github.atengk.boot.sample.realtime.controller;

import cn.dev33.satoken.stp.StpUtil;
import io.github.atengk.boot.core.model.Result;
import io.github.atengk.boot.realtime.model.RealtimeMessage;
import io.github.atengk.boot.realtime.model.SessionKey;
import io.github.atengk.boot.realtime.sse.SseStreamHelper;
import io.github.atengk.boot.realtime.template.RealtimeTemplate;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;

/**
 * 实时通信 REST 演示控制器
 * <p>
 * 演示使用 RealtimeTemplate 触发向在线用户单播、组播与全服广播，
 * 以及使用 SseStreamHelper 开启打字机流式异步输出。
 * </p>
 *
 * @author Ateng
 * @since 2026-10-21
 */
@Slf4j
@RestController
@RequestMapping("/api/realtime")
@RequiredArgsConstructor
public class RealtimeDemoController {

    private final RealtimeTemplate realtimeTemplate;
    private final ObjectProvider<Executor> taskExecutorProvider;

    /**
     * 模拟用户登录并返回 Token (供测试与演示)
     */
    @PostMapping("/login")
    public Result<String> login(@RequestParam String userId) {
        StpUtil.login(userId);
        return Result.success(StpUtil.getTokenValue());
    }

    /**
     * 向指定用户单播消息
     */
    @PostMapping("/send-user")
    public Result<Integer> sendToUser(@RequestParam String userId, @RequestParam String message) {
        int count = realtimeTemplate.sendToUser(userId, RealtimeMessage.of("notice:user", message));
        return Result.success(count);
    }

    /**
     * 仅当指定客户端为 WebSocket 协议时定向专线单播
     */
    @PostMapping("/send-websocket")
    public Result<Boolean> sendViaWebSocket(@RequestParam String userId,
                                            @RequestParam String clientId,
                                            @RequestParam String message) {
        SessionKey key = SessionKey.of(userId, clientId);
        boolean success = realtimeTemplate.sendViaWebSocket(key, RealtimeMessage.of("notice:ws", message));
        return Result.success(success);
    }

    /**
     * 仅当指定客户端为 SSE 协议时定向专线单播
     */
    @PostMapping("/send-sse")
    public Result<Boolean> sendViaSse(@RequestParam String userId,
                                      @RequestParam String clientId,
                                      @RequestParam String message) {
        SessionKey key = SessionKey.of(userId, clientId);
        boolean success = realtimeTemplate.sendViaSse(key, RealtimeMessage.of("notice:sse", message));
        return Result.success(success);
    }

    /**
     * 向指定分组/房间组播下发消息
     */
    @PostMapping("/send-group")
    public Result<Integer> sendToGroup(@RequestParam String groupId, @RequestParam String message) {
        int count = realtimeTemplate.sendToGroup(groupId, RealtimeMessage.of("notice:group", message));
        return Result.success(count);
    }

    /**
     * 全服广播公告
     */
    @PostMapping("/broadcast")
    public Result<Integer> broadcast(@RequestParam String message) {
        int count = realtimeTemplate.sendToAll(RealtimeMessage.of("notice:all", message));
        return Result.success(count);
    }

    /**
     * 主动驱逐指定用户下线
     */
    @PostMapping("/kickout")
    public Result<Integer> kickout(@RequestParam String userId) {
        int count = realtimeTemplate.kickoutUser(userId);
        return Result.success(count);
    }

    /**
     * 获取当前节点在线统计
     */
    @GetMapping("/metrics")
    public Result<Integer> getOnlineCount() {
        return Result.success(realtimeTemplate.getOnlineCount());
    }

    /**
     * 演示 LLM 风格打字机异步流式推流
     */
    @GetMapping(value = "/stream-llm", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter streamLlm() {
        SseEmitter emitter = SseStreamHelper.create(60_000L);
        Runnable task = () -> {
            try {
                String[] words = {"Hello", " ", "World", "!", " ", "This", " ", "is", " ", "ateng-boot-realtime", " ", "streaming."};
                for (String word : words) {
                    Thread.sleep(50);
                    boolean sent = SseStreamHelper.sendChunk(emitter, word);
                    if (!sent) {
                        log.debug("[Sample] 客户端已断开打字机推流");
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
