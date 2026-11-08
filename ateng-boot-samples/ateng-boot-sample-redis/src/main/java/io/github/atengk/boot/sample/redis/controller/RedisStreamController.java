package io.github.atengk.boot.sample.redis.controller;

import io.github.atengk.boot.core.model.Result;
import io.github.atengk.boot.core.util.IdUtil;
import io.github.atengk.boot.redis.stream.model.StreamDeadLetterMessage;
import io.github.atengk.boot.sample.redis.model.OrderStreamEvent;
import io.github.atengk.boot.sample.redis.model.stream.OrderStreamSendRequest;
import io.github.atengk.boot.sample.redis.model.stream.PendingMessageVO;
import io.github.atengk.boot.sample.redis.model.stream.StreamClaimRequest;
import io.github.atengk.boot.sample.redis.model.stream.StreamEventVO;
import io.github.atengk.boot.sample.redis.model.stream.StreamPendingSummaryVO;
import io.github.atengk.boot.sample.redis.model.stream.StreamTrimRequest;
import io.github.atengk.boot.sample.redis.service.StreamEventScenarioService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.connection.stream.MapRecord;
import org.springframework.data.redis.connection.stream.PendingMessage;
import org.springframework.data.redis.connection.stream.PendingMessages;
import org.springframework.data.redis.connection.stream.PendingMessagesSummary;
import org.springframework.data.redis.connection.stream.RecordId;
import org.springframework.util.StringUtils;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;

/**
 * Redis 5.0+ Stream 消息流控制器
 * 演示可靠消息追加、静态门面投递、声明式消费组监听、PEL 待决探查、XCLAIM 认领、XTRIM 裁剪与死信流转
 *
 * @author Ateng
 * @since 2026-11-08
 */
@Slf4j
@Validated
@RestController
@RequestMapping("/sample/redis/stream")
@RequiredArgsConstructor
public class RedisStreamController {

    private final StreamEventScenarioService streamEventScenarioService;

    /**
     * 基于受管模板 RedisStreamTemplate 投递订单流事件
     *
     * @param request 投递请求参数
     * @return 投递结果 VO
     */
    @PostMapping("/events/template")
    public Result<StreamEventVO> sendEventByTemplate(@Valid @RequestBody OrderStreamSendRequest request) {
        log.info("[RedisStreamController] 模板门面发送订单流事件: orderId={}", request.getOrderId());
        OrderStreamEvent event = OrderStreamEvent.builder()
                .orderId(request.getOrderId())
                .eventType(request.getEventType())
                .amount(request.getAmount())
                .timestamp(System.currentTimeMillis())
                .build();

        RecordId recordId = streamEventScenarioService.sendOrderEvent(event);

        StreamEventVO vo = StreamEventVO.builder()
                .recordId(recordId != null ? recordId.getValue() : null)
                .orderId(request.getOrderId())
                .eventType(request.getEventType())
                .amount(request.getAmount())
                .eventTime(LocalDateTime.now())
                .build();
        return Result.success(vo);
    }

    /**
     * 基于静态工具门面 RedisStreamUtil 投递订单流事件
     *
     * @param request 投递请求参数
     * @return 投递结果 VO
     */
    @PostMapping("/events/static")
    public Result<StreamEventVO> sendEventByStatic(@Valid @RequestBody OrderStreamSendRequest request) {
        log.info("[RedisStreamController] 静态门面发送订单流事件: orderId={}", request.getOrderId());
        OrderStreamEvent event = OrderStreamEvent.builder()
                .orderId(request.getOrderId())
                .eventType(request.getEventType())
                .amount(request.getAmount())
                .timestamp(System.currentTimeMillis())
                .build();

        RecordId recordId = streamEventScenarioService.sendOrderEventStatic(event);

        StreamEventVO vo = StreamEventVO.builder()
                .recordId(recordId != null ? recordId.getValue() : null)
                .orderId(request.getOrderId())
                .eventType(request.getEventType())
                .amount(request.getAmount())
                .eventTime(LocalDateTime.now())
                .build();
        return Result.success(vo);
    }

    /**
     * 查询已被 @RedisStreamListener 声明式消费组消费的订单事件列表
     *
     * @return 已消费订单事件列表
     */
    @GetMapping("/consumed")
    public Result<List<OrderStreamEvent>> getConsumedEvents() {
        return Result.success(streamEventScenarioService.getReceivedEvents());
    }

    /**
     * 查询消费组待决消息概要统计 (XPENDING summary)
     *
     * @return 待决消息概要统计 VO
     */
    @GetMapping("/pending/summary")
    public Result<StreamPendingSummaryVO> getPendingSummary() {
        PendingMessagesSummary summary = streamEventScenarioService.getPendingSummary();
        if (summary == null) {
            return Result.success(StreamPendingSummaryVO.builder()
                    .stream(StreamEventScenarioService.STREAM_ORDER_EVENTS)
                    .group(StreamEventScenarioService.GROUP_ORDER_PROCESSOR)
                    .totalPending(0L)
                    .consumerSummary(Collections.emptyMap())
                    .build());
        }

        String minId = null;
        String maxId = null;
        try {
            minId = summary.minMessageId();
        } catch (Exception ignored) {
        }
        try {
            maxId = summary.maxMessageId();
        } catch (Exception ignored) {
        }

        StreamPendingSummaryVO vo = StreamPendingSummaryVO.builder()
                .stream(StreamEventScenarioService.STREAM_ORDER_EVENTS)
                .group(summary.getGroupName())
                .totalPending(summary.getTotalPendingMessages())
                .minId(minId)
                .maxId(maxId)
                .consumerSummary(summary.getPendingMessagesPerConsumer())
                .build();
        return Result.success(vo);
    }

    /**
     * 查询消费组待决消息详细明细列表 (XPENDING detail)
     *
     * @param count 拉取上限条数
     * @return 待决消息明细列表
     */
    @GetMapping("/pending/messages")
    public Result<List<PendingMessageVO>> getPendingMessages(@RequestParam(value = "count", defaultValue = "10") long count) {
        PendingMessages pending = streamEventScenarioService.getPendingMessages(count);
        if (pending == null) {
            return Result.success(Collections.emptyList());
        }
        List<PendingMessageVO> list = new ArrayList<>();
        for (PendingMessage pm : pending) {
            list.add(PendingMessageVO.builder()
                    .idAsString(pm.getIdAsString())
                    .consumerName(pm.getConsumerName())
                    .idleTimeMs(pm.getElapsedTimeSinceLastDelivery() != null ? pm.getElapsedTimeSinceLastDelivery().toMillis() : 0L)
                    .deliveryCount(pm.getTotalDeliveryCount())
                    .build());
        }
        return Result.success(list);
    }

    /**
     * 认领孤儿待决消息 (XCLAIM)
     *
     * @param request 认领请求参数
     * @return 成功认领的消息 ID 列表
     */
    @PostMapping("/claim")
    public Result<List<String>> claimOrphans(@Valid @RequestBody StreamClaimRequest request) {
        log.info("[RedisStreamController] 认领孤儿待决消息: stream={}, group={}, newConsumer={}",
                request.getStream(), request.getGroup(), request.getNewConsumer());
        List<MapRecord<String, Object, Object>> claimed = streamEventScenarioService.claimOrphan(
                request.getNewConsumer(),
                Duration.ofSeconds(request.getMinIdleSeconds()),
                request.getRecordIds().toArray(String[]::new)
        );
        List<String> claimedIds = new ArrayList<>();
        if (claimed != null) {
            for (MapRecord<String, Object, Object> record : claimed) {
                claimedIds.add(record.getId().getValue());
            }
        }
        return Result.success(claimedIds);
    }

    /**
     * 定长修剪裁剪 Stream 长度 (XTRIM)
     *
     * @param request 裁剪请求参数
     * @return 被修剪移除的消息条数
     */
    @PostMapping("/trim")
    public Result<Long> trimStream(@Valid @RequestBody StreamTrimRequest request) {
        log.info("[RedisStreamController] 裁剪 Stream 长度: stream={}, maxLen={}",
                request.getStream(), request.getMaxLen());
        Long trimmed = streamEventScenarioService.trimStream(request.getMaxLen());
        return Result.success(trimmed != null ? trimmed : 0L);
    }

    /**
     * 投递毒丸 Stream 事件演练任务
     * 毒丸消息将触发声明式消费组监听抛出业务异常，经 3 次重试耗尽后自动转移至死信流并触发 SPI 告警
     *
     * @param orderId 毒丸订单 ID（可选，默认自动生成）
     * @return 演练执行提示
     */
    @PostMapping("/events/poison")
    public Result<String> sendPoisonEvent(@RequestParam(value = "orderId", required = false) String orderId) {
        String effectiveOrderId = StringUtils.hasText(orderId) ? orderId : "POISON-STREAM-" + IdUtil.fastSimpleUUID();
        log.warn("[RedisStreamController] 投递毒丸 Stream 事件: orderId={}", effectiveOrderId);
        RecordId recordId = streamEventScenarioService.sendPoisonOrderEvent(effectiveOrderId);
        return Result.success("毒丸 Stream 事件投递成功: recordId=" + (recordId != null ? recordId.getValue() : "null")
                + ", orderId=" + effectiveOrderId + "，请观察重试日志与死信流转");
    }

    /**
     * 查询死信流消息记录清单 ({stream}:dlq)
     *
     * @param count 拉取上限条数
     * @return 死信消息记录列表
     */
    @GetMapping("/dead-letter")
    public Result<List<Map<String, Object>>> getDeadLetterRecords(@RequestParam(value = "count", defaultValue = "10") long count) {
        List<Map<String, Object>> records = streamEventScenarioService.getDeadLetterRecords(count);
        return Result.success(records);
    }

    /**
     * 查询 StreamFailureHandler SPI 捕获的最近失败告警快照列表
     *
     * @return 告警快照列表
     */
    @GetMapping("/alerts")
    public Result<List<StreamDeadLetterMessage>> getAlerts() {
        return Result.success(streamEventScenarioService.getRecentAlerts());
    }
}
