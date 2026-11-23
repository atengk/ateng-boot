package io.github.atengk.boot.sample.rabbitmq.controller;

import io.github.atengk.boot.core.model.Result;
import io.github.atengk.boot.sample.rabbitmq.consumer.AnonymousBroadcastConsumer;
import io.github.atengk.boot.sample.rabbitmq.dto.BroadcastPublishRequest;
import io.github.atengk.boot.sample.rabbitmq.producer.TradeMessageProducer;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * Fanout 广播与动态匿名临时排他队列体验控制器
 * 演示向 Fanout 交换机分发广播与动态匿名临时队列实时接收
 *
 * @author Ateng
 * @since 2026-11-09
 */
@Slf4j
@RestController
@RequestMapping("/sample/rabbitmq/broadcast")
@RequiredArgsConstructor
public class RabbitBroadcastController {

    private final TradeMessageProducer producer;
    private final AnonymousBroadcastConsumer broadcastConsumer;

    /**
     * 向 Fanout 交换机发布广播通知
     *
     * @param request 广播请求
     * @return 响应消息
     */
    @PostMapping("/publish")
    public Result<String> publishBroadcast(@Valid @RequestBody BroadcastPublishRequest request) {
        producer.sendBroadcast(request.getMessage());
        return Result.success(request.getMessage(), "Fanout 广播消息投递成功，所有绑定的临时排他队列均将收到通知");
    }

    /**
     * 探查当前实例动态匿名临时队列接收到的广播消息列表
     *
     * @return 广播消息列表
     */
    @GetMapping("/received")
    public Result<List<String>> getReceivedBroadcasts() {
        return Result.success(broadcastConsumer.getReceivedBroadcasts());
    }

    /**
     * 清空广播消费记录
     *
     * @return 操作结果
     */
    @DeleteMapping("/clear")
    public Result<Boolean> clearBroadcasts() {
        broadcastConsumer.clear();
        return Result.success(true, "广播消费记录已清空");
    }
}
