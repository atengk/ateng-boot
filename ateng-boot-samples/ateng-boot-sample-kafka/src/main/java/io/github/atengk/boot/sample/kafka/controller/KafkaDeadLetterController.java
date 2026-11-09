package io.github.atengk.boot.sample.kafka.controller;

import io.github.atengk.boot.core.exception.IdempotentException;
import io.github.atengk.boot.core.model.Result;
import io.github.atengk.boot.kafka.helper.KafkaDeadLetterHelper.DeadLetterRecord;
import io.github.atengk.boot.sample.kafka.consumer.DeadLetterAuditConsumer;
import io.github.atengk.boot.sample.kafka.consumer.IdempotentKafkaConsumer;
import io.github.atengk.boot.sample.kafka.dto.DeadLetterPeekResponse;
import io.github.atengk.boot.sample.kafka.dto.DeadLetterReplayRequest;
import io.github.atengk.boot.sample.kafka.dto.OrderProduceRequest;
import io.github.atengk.boot.sample.kafka.model.OrderMessage;
import io.github.atengk.boot.sample.kafka.service.DeadLetterReplayScenarioService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.nio.charset.StandardCharsets;
import java.util.List;

/**
 * 毒丸隔离、消费幂等与死信受控治理控制器
 *
 * <p>统一暴露毒丸报文投递、消费端幂等防重拦截与静默确认、死信结构化诊断、
 * 死信只读探查（peek）、受控清洗重放（replay）与物理清空（purge）端点。</p>
 *
 * @author Ateng
 * @since 2026-11-09
 */
@Slf4j
@RestController
@RequestMapping("/sample/kafka/dead-letter")
@RequiredArgsConstructor
public class KafkaDeadLetterController {

    private final DeadLetterReplayScenarioService deadLetterService;
    private final DeadLetterAuditConsumer deadLetterAuditConsumer;
    private final IdempotentKafkaConsumer idempotentConsumer;

    /**
     * 投递格式畸形毒丸报文（0次无效重试快速分流至死信主题）
     *
     * @param poisonData 毒丸消息内容（默认 CORRUPTED_POISON）
     * @return 投递结果
     */
    @PostMapping("/trigger-poison")
    public Result<String> triggerPoison(
            @RequestParam(value = "poisonData", required = false, defaultValue = "CORRUPTED_POISON") String poisonData) {
        log.warn("[HTTP-POISON-TRIGGER] 投递毒丸报文: poisonData={}", poisonData);
        deadLetterService.sendPoisonPill(poisonData);
        return Result.success("毒丸报文投递成功，已触发死信分流: " + poisonData);
    }

    /**
     * 投递业务订单触发消费端幂等防重检测
     *
     * @param request 订单投递请求
     * @param silent  重复消费时是否由消费端静默提交位移（默认 true 静默提交）
     * @return 消费处理结果
     */
    @PostMapping("/trigger-idempotent")
    public Result<String> triggerIdempotent(
            @Valid @RequestBody OrderProduceRequest request,
            @RequestParam(value = "silent", required = false, defaultValue = "true") boolean silent) {
        OrderMessage order = request.toOrderMessage();
        try {
            idempotentConsumer.processOrderWithIdempotentCheck(order);
            return Result.success("首次消费成功: " + order.getOrderId());
        } catch (IdempotentException e) {
            log.warn("[HTTP-IDEMPOTENT-HIT] 捕获幂等防重拦截异常: orderId={}", order.getOrderId());
            if (silent) {
                return Result.success("幂等防重拦截成功，已静默提交位移并阻断进入死信: " + order.getOrderId());
            }
            throw e;
        }
    }

    /**
     * 探查死信诊断工具（KafkaDeadLetterHelper）解析的 7 类 DLT 结构化记录
     *
     * @return 死信诊断记录列表
     */
    @GetMapping("/audit-records")
    public Result<List<DeadLetterRecord>> getAuditRecords() {
        return Result.success(deadLetterAuditConsumer.getDiagnosedRecords());
    }

    /**
     * 只读探查死信主题积压消息（不推进消费位移）
     *
     * @param dltTopic 死信主题名称 (默认 sample.poison.topic.DLT)
     * @param limit    探查条数上限 (默认 10)
     * @return 死信探查结果明细列表
     */
    @GetMapping("/peek")
    public Result<List<DeadLetterPeekResponse>> peek(
            @RequestParam(value = "dltTopic", required = false, defaultValue = "sample.poison.topic.DLT") String dltTopic,
            @RequestParam(value = "limit", required = false, defaultValue = "10") int limit) {
        log.info("[HTTP-DLT-PEEK] 只读探查死信队列: dltTopic={}, limit={}", dltTopic, limit);
        List<ConsumerRecord<byte[], byte[]>> records = deadLetterService.peekDeadLetters(dltTopic, limit);
        List<DeadLetterPeekResponse> responses = records.stream()
                .map(r -> DeadLetterPeekResponse.builder()
                        .topic(r.topic())
                        .partition(r.partition())
                        .offset(r.offset())
                        .key(r.key() != null ? new String(r.key(), StandardCharsets.UTF_8) : null)
                        .payload(r.value() != null ? new String(r.value(), StandardCharsets.UTF_8) : null)
                        .build())
                .toList();
        return Result.success(responses);
    }

    /**
     * 死信受控清洗重放至目标业务主题
     *
     * @param request 重放请求参数
     * @return 实际重放消息计数
     */
    @PostMapping("/replay")
    public Result<Integer> replay(@Valid @RequestBody DeadLetterReplayRequest request) {
        log.info("[HTTP-DLT-REPLAY] 执行死信安全重放: dlqTopic={}, targetTopic={}, limit={}",
                request.getDlqTopic(), request.getTargetTopic(), request.getLimit());
        int count = deadLetterService.replayDeadLetters(
                request.getDlqTopic(),
                request.getTargetTopic(),
                request.getLimit()
        );
        return Result.success(count);
    }

    /**
     * 物理清空死信队列位移积压
     *
     * @param dltTopic 死信主题名称 (默认 sample.poison.topic.DLT)
     * @return 清空结果提示
     */
    @PostMapping("/purge")
    public Result<String> purge(
            @RequestParam(value = "dltTopic", required = false, defaultValue = "sample.poison.topic.DLT") String dltTopic) {
        log.info("[HTTP-DLT-PURGE] 物理清空死信队列: dltTopic={}", dltTopic);
        deadLetterService.purgeDeadLetters(dltTopic);
        return Result.success("死信主题物理清空成功: " + dltTopic);
    }

    /**
     * 清理审计与幂等状态
     *
     * @return 操作成功结果
     */
    @PostMapping("/clear")
    public Result<Void> clear() {
        deadLetterAuditConsumer.clear();
        idempotentConsumer.clear();
        return Result.success();
    }
}
