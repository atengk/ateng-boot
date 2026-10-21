package io.github.atengk.boot.sample.realtime.controller;

import cn.dev33.satoken.stp.StpUtil;
import io.github.atengk.boot.core.model.Result;
import io.github.atengk.boot.realtime.model.RealtimeMessage;
import io.github.atengk.boot.realtime.sse.SseStreamHelper;
import io.github.atengk.boot.realtime.template.RealtimeTemplate;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.concurrent.CompletableFuture;

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
        CompletableFuture.runAsync(() -> {
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
        });
        return emitter;
    }
}
