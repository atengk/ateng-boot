package io.github.atengk.boot.sample.rabbitmq.controller;

import io.github.atengk.boot.core.model.Result;
import io.github.atengk.boot.rabbitmq.helper.RabbitDeadLetterHelper;
import io.github.atengk.boot.sample.rabbitmq.consumer.DeadLetterAuditConsumer;
import io.github.atengk.boot.sample.rabbitmq.consumer.IdempotentOrderConsumer;
import io.github.atengk.boot.sample.rabbitmq.consumer.PoisonPillConsumer;
import io.github.atengk.boot.sample.rabbitmq.dto.DeadLetterReplayRequest;
import io.github.atengk.boot.sample.rabbitmq.producer.TradeMessageProducer;
import io.github.atengk.boot.sample.rabbitmq.service.DeadLetterReplayService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.core.Message;
import org.springframework.web.bind.annotation.*;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

/**
 * 死信受控治理、幂等静默确认与毒丸隔离体验控制器
 * 演示 RabbitDeadLetterReplayTemplate 受控重放、peek/purge、@Idempotent Silent ACK 与毒丸 0 次重试隔离
 *
 * @author Ateng
 * @since 2026-11-09
 */
@Slf4j
@RestController
@RequestMapping("/sample/rabbitmq/dead-letter")
@RequiredArgsConstructor
public class RabbitDeadLetterController {

    private final TradeMessageProducer producer;
    private final DeadLetterReplayService replayService;
    private final DeadLetterAuditConsumer deadLetterAuditConsumer;
    private final IdempotentOrderConsumer idempotentConsumer;
    private final PoisonPillConsumer poisonPillConsumer;

    /**
     * 触发致命毒丸消息 (0 次重试快速路由送 DLQ 死信队列)
     *
     * @param bizKey 业务主键
     * @return 响应说明
     */
    @PostMapping("/trigger-poison")
    public Result<String> triggerPoisonPill(@RequestParam(defaultValue = "biz-poison-01") String bizKey) {
        producer.sendBizMessage(bizKey, "poison-pill");
        return Result.success(bizKey, "毒丸消息已投递，触发 RabbitFatalExceptionStrategy 快速隔离送入死信队列");
    }

    /**
     * 触发幂等测试消息 (首次投递成功，重复投递触发 IdempotentException 并由全局切面静默确认 Silent ACK)
     *
     * @param bizKey 业务去重主键
     * @return 响应说明
     */
    @PostMapping("/trigger-idempotent")
    public Result<String> triggerIdempotent(@RequestParam(defaultValue = "order-idemp-1001") String bizKey) {
        producer.sendIdempotentMessage(bizKey, "IDEMPOTENT_PAYLOAD_" + bizKey);
        return Result.success(bizKey, "幂等测试消息投递完成（重复相同 bizKey 将触发 Silent ACK 阻断重试风暴）");
    }

    /**
     * 死信受控重放 (自动清洗 x-death 元数据、注入溯源标记并防二次死信雪崩熔断)
     *
     * @param request 重放请求
     * @return 成功重放数量
     */
    @PostMapping("/replay")
    public Result<Integer> replayDeadLetters(@Valid @RequestBody DeadLetterReplayRequest request) {
        int count = replayService.replayBizDeadLetters(request.getLimit());
        return Result.success(count, "死信受控重放完成，实际重新入队消息数: " + count);
    }

    /**
     * 只读探查死信队列头部积压消息 (利用 basicNack(requeue=true) 探查不消费)
     *
     * @param limit 探查条数
     * @return 探查到的消息体摘要列表
     */
    @GetMapping("/peek")
    public Result<List<String>> peekDeadLetters(@RequestParam(defaultValue = "5") int limit) {
        List<Message> messages = replayService.peekBizDeadLetters(limit);
        List<String> payloads = messages.stream()
                .map(msg -> new String(msg.getBody(), StandardCharsets.UTF_8))
                .toList();
        return Result.success(payloads);
    }

    /**
     * 清空死信队列全部积压消息
     *
     * @return 清空被删除的消息条数
     */
    @PostMapping("/purge")
    public Result<Integer> purgeDeadLetters() {
        int purged = replayService.purgeBizDeadLetters();
        return Result.success(purged, "死信队列清空成功，清除条数: " + purged);
    }

    /**
     * 探查死信审计消费者接收到的元数据诊断记录
     *
     * @return 死信诊断元数据列表
     */
    @GetMapping("/audit-records")
    public Result<List<RabbitDeadLetterHelper.DeadLetterRecord>> getAuditRecords() {
        return Result.success(deadLetterAuditConsumer.getAuditRecords());
    }

    /**
     * 探查消费端防重幂等统计
     *
     * @return 统计映射 (normalCount, idempotentTriggeredCount)
     */
    @GetMapping("/idempotent-counts")
    public Result<Map<String, Integer>> getIdempotentCounts() {
        return Result.success(Map.of(
                "normalProcessedCount", idempotentConsumer.getNormalProcessedCount(),
                "idempotentTriggeredCount", idempotentConsumer.getIdempotentTriggeredCount()
        ));
    }

    /**
     * 清空死信与幂等审计记录
     *
     * @return 操作结果
     */
    @DeleteMapping("/clear")
    public Result<Boolean> clearAuditRecords() {
        deadLetterAuditConsumer.clear();
        idempotentConsumer.clear();
        poisonPillConsumer.clear();
        return Result.success(true, "死信与幂等审计状态已清空");
    }
}
