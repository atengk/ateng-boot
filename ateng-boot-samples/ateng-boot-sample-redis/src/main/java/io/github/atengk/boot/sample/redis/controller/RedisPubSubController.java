package io.github.atengk.boot.sample.redis.controller;

import io.github.atengk.boot.core.model.Result;
import io.github.atengk.boot.sample.redis.model.NoticeBroadcastPayload;
import io.github.atengk.boot.sample.redis.model.pubsub.BroadcastResultVO;
import io.github.atengk.boot.sample.redis.model.pubsub.NoticeBroadcastRequest;
import io.github.atengk.boot.sample.redis.model.pubsub.SystemEventBroadcastRequest;
import io.github.atengk.boot.sample.redis.service.NoticeBroadcastScenarioService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * Redis Pub/Sub 发布订阅与主题广播控制器
 * 演示精确主题全站公告广播发布、通配模式多模块配置刷新广播与接收历史探查
 *
 * @author Ateng
 * @since 2026-11-08
 */
@Slf4j
@Validated
@RestController
@RequestMapping("/sample/redis/pubsub")
@RequiredArgsConstructor
public class RedisPubSubController {

    private final NoticeBroadcastScenarioService noticeBroadcastScenarioService;

    /**
     * 向指定精确频道广播发布全站系统维护公告
     *
     * @param request 公告发布请求参数
     * @return 广播发布结果 VO
     */
    @PostMapping("/broadcast/notice")
    public Result<BroadcastResultVO> broadcastNotice(@Valid @RequestBody NoticeBroadcastRequest request) {
        log.info("[RedisPubSubController] 广播全站系统维护公告: noticeId={}, title={}",
                request.getNoticeId(), request.getTitle());
        NoticeBroadcastPayload payload = new NoticeBroadcastPayload(
                request.getNoticeId(),
                request.getTitle(),
                request.getContent(),
                System.currentTimeMillis()
        );

        Long subscribers = noticeBroadcastScenarioService.publishNotice(payload);

        BroadcastResultVO vo = BroadcastResultVO.builder()
                .topic(NoticeBroadcastScenarioService.TOPIC_NOTICE_BROADCAST)
                .subscriberCount(subscribers != null ? subscribers : 0L)
                .success(true)
                .build();
        return Result.success(vo);
    }

    /**
     * 向通配模式频道 (sample:system:*) 广播发布系统配置刷新事件
     *
     * @param request 系统事件广播请求参数
     * @return 广播发布结果 VO
     */
    @PostMapping("/broadcast/system-config")
    public Result<BroadcastResultVO> broadcastSystemConfig(@Valid @RequestBody SystemEventBroadcastRequest request) {
        log.info("[RedisPubSubController] 广播系统通配事件: eventType={}, data={}",
                request.getEventType(), request.getEventData());
        Long subscribers = noticeBroadcastScenarioService.publishSystemEvent(
                request.getEventType(),
                request.getEventData()
        );

        BroadcastResultVO vo = BroadcastResultVO.builder()
                .topic("sample:system:" + request.getEventType())
                .subscriberCount(subscribers != null ? subscribers : 0L)
                .success(true)
                .build();
        return Result.success(vo);
    }

    /**
     * 查询已被 @ChannelTopicListener 精确主题监听接收的系统公告广播历史记录
     *
     * @return 已接收系统公告列表
     */
    @GetMapping("/history/notices")
    public Result<List<NoticeBroadcastPayload>> getNoticeHistory() {
        return Result.success(noticeBroadcastScenarioService.getReceivedNotices());
    }

    /**
     * 查询已被 @ChannelTopicListener 通配模式监听接收的系统事件历史记录
     *
     * @return 已接收系统事件记录列表
     */
    @GetMapping("/history/system-events")
    public Result<List<String>> getSystemEventHistory() {
        return Result.success(noticeBroadcastScenarioService.getReceivedSystemEvents());
    }
}
