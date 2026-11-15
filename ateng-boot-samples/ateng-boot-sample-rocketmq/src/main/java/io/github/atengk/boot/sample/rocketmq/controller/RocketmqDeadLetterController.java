package io.github.atengk.boot.sample.rocketmq.controller;

import io.github.atengk.boot.core.model.Result;
import io.github.atengk.boot.rocketmq.model.DeadLetterReplayResult;
import io.github.atengk.boot.rocketmq.model.RocketmqDeadLetterRecord;
import io.github.atengk.boot.sample.rocketmq.dto.*;
import io.github.atengk.boot.sample.rocketmq.service.DeadLetterReplayScenarioService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.rocketmq.client.apis.message.MessageView;
import org.springframework.util.StringUtils;
import org.springframework.web.bind.annotation.*;

import java.util.ArrayList;
import java.util.List;

/**
 * RocketMQ 死信队列治理与受控重放场景控制器
 *
 * <p>提供死信只读探查（零 ACK）、受控重放（防二次雪崩熔断防线）以及物理确认丢弃端点。</p>
 *
 * @author Ateng
 * @since 2026-11-10
 */
@Slf4j
@RestController
@RequestMapping("/sample/rocketmq/dead-letter")
@RequiredArgsConstructor
public class RocketmqDeadLetterController {

    private final DeadLetterReplayScenarioService deadLetterService;

    /**
     * 只读探查目标消费组的死信队列（零 ACK 保持现场，租约到期自动恢复）
     *
     * @param request 探查请求参数
     * @return 死信消息详情列表
     */
    @PostMapping("/peek")
    public Result<List<DeadLetterMessageVO>> peekDeadLetters(@Valid @RequestBody DeadLetterPeekRequest request) {
        List<MessageView> messages = deadLetterService.peekDeadLetters(
                request.getConsumerGroup(),
                request.resolveLimit()
        );
        List<DeadLetterMessageVO> vos = new ArrayList<>(messages.size());
        for (MessageView mv : messages) {
            vos.add(DeadLetterReplayScenarioService.convertToMessageVO(mv));
        }
        return Result.success(vos);
    }

    /**
     * 结构化诊断探查目标消费组的死信队列（零 ACK，附带失败原因、异常堆栈与 GZIP/HEX 自适应载荷视图）
     *
     * @param request 探查请求参数
     * @return 结构化死信诊断记录列表
     */
    @PostMapping("/peek-diagnosed")
    public Result<List<RocketmqDeadLetterRecord>> peekDiagnosedDeadLetters(@Valid @RequestBody DeadLetterPeekRequest request) {
        return Result.success(deadLetterService.peekDiagnosedDeadLetters(
                request.getConsumerGroup(),
                request.resolveLimit()
        ));
    }

    /**
     * 触发投递毒丸消息（用于端到端演示消费端毒丸防护、%DLQ% 自动转储与诊断闭环）
     *
     * @param request 可选毒丸触发参数（默认 FATAL 模式）
     * @return 毒丸消息投递回执
     */
    @PostMapping("/poison-pill")
    public Result<OrderProduceResponse> triggerPoisonPill(
            @Valid @RequestBody(required = false) DeadLetterPoisonPillRequest request) {
        return Result.success(deadLetterService.triggerPoisonPill(request));
    }

    /**
     * 受控重放目标消费组的死信至业务主题（支持目标主题重定向与防二次雪崩熔断防护）
     *
     * @param request 重放请求参数
     * @return 重放汇总结果视图
     */
    @PostMapping("/replay")
    public Result<DeadLetterReplayVO> replayDeadLetters(@Valid @RequestBody DeadLetterReplayRequest request) {
        DeadLetterReplayResult result;
        if (StringUtils.hasText(request.getTargetTopic())) {
            result = deadLetterService.replayDeadLettersToTopic(
                    request.getConsumerGroup(),
                    request.resolveLimit(),
                    request.getTargetTopic().trim()
            );
        } else {
            result = deadLetterService.replayDeadLetters(
                    request.getConsumerGroup(),
                    request.resolveLimit()
            );
        }
        return Result.success(DeadLetterReplayScenarioService.convertToReplayVO(result));
    }

    /**
     * 批量丢弃目标消费组的死信消息并物理确认出队
     *
     * @param request 丢弃请求参数
     * @return 本次成功丢弃确认的消息条数
     */
    @PostMapping("/discard")
    public Result<Integer> discardDeadLetters(@Valid @RequestBody DeadLetterDiscardRequest request) {
        int count = deadLetterService.discardDeadLetters(
                request.getConsumerGroup(),
                request.resolveLimit()
        );
        return Result.success(count);
    }
}
