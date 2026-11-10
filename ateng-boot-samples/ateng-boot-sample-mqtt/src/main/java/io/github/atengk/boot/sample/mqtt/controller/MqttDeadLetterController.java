package io.github.atengk.boot.sample.mqtt.controller;

import io.github.atengk.boot.core.model.Result;
import io.github.atengk.boot.mqtt.model.MqttDeadLetterRecord;
import io.github.atengk.boot.sample.mqtt.dto.DeadLetterReplayRequest;
import io.github.atengk.boot.sample.mqtt.service.DeadLetterReplayScenarioService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.*;

import java.time.Duration;
import java.util.List;

/**
 * 毒丸死信（DLT）隔离与受控重放治理体验端控制器
 * 提供毒丸异常触发、死信只读离线窥探、受控重放防二次雪崩与确定性清空体验
 *
 * @author Ateng
 * @since 2026-11-10
 */
@Slf4j
@RestController
@RequestMapping("/sample/mqtt/dead-letter")
@RequiredArgsConstructor
public class MqttDeadLetterController {

    private final DeadLetterReplayScenarioService dltScenarioService;

    /**
     * 投送毒丸报文触发不可逆异常并自动转移至 DLT 主题
     *
     * @param payload 毒丸载荷（默认为 POISON_PAYLOAD）
     * @return 触发提示
     */
    @PostMapping("/trigger-poison")
    public Result<String> triggerPoisonPill(@RequestParam(value = "payload", defaultValue = "POISON_PAYLOAD") String payload) {
        log.info("[HTTP-DLT] 投送毒丸测试载荷: {}", payload);
        dltScenarioService.triggerPoisonPill(payload);
        return Result.success("毒丸报文已投递，触发不可逆致命异常并自动转移至 DLT 死信主题: " + payload);
    }

    /**
     * 只读离线窥探死信主题中的积压报文
     *
     * @param dltTopic       死信主题
     * @param count          最大窥探条数
     * @param timeoutSeconds 采样超时时间
     * @return 死信记录列表
     */
    @GetMapping("/peek")
    public Result<List<MqttDeadLetterRecord>> peekDeadLetters(
            @RequestParam(value = "topic", defaultValue = "iot/devices/poison-test/DLT") String dltTopic,
            @RequestParam(value = "count", defaultValue = "10") int count,
            @RequestParam(value = "timeoutSeconds", defaultValue = "3") int timeoutSeconds) {
        log.info("[HTTP-DLT] 离线窥探死信主题 [{}] 报文: count={}, timeout={}s", dltTopic, count, timeoutSeconds);
        List<MqttDeadLetterRecord> records = dltScenarioService.peekDeadLetters(dltTopic, count, Duration.ofSeconds(timeoutSeconds));
        return Result.success(records);
    }

    /**
     * 受控重放死信报文至目标恢复主题
     *
     * @param request 重放请求
     * @return 实际重放成功条数
     */
    @PostMapping("/replay")
    public Result<String> replayDeadLetters(@Valid @RequestBody DeadLetterReplayRequest request) {
        log.info("[HTTP-DLT] 接收死信重放请求: source={}, target={}, count={}",
                request.getSourceTopic(), request.getTargetTopic(), request.getCount());
        Duration timeout = Duration.ofSeconds(request.getTimeoutSeconds() != null ? request.getTimeoutSeconds() : 5);
        int replayed = dltScenarioService.replayDeadLetters(
                request.getSourceTopic(),
                request.getTargetTopic(),
                request.getCount(),
                timeout
        );
        return Result.success("成功受控重放 " + replayed + " 条死信报文至目标主题 [" + request.getTargetTopic() + "]");
    }

    /**
     * 确定性彻底清空指定死信主题
     *
     * @param dltTopic 死信主题
     * @return 清空结果提示
     */
    @DeleteMapping("/purge")
    public Result<String> purgeDeadLetters(@RequestParam(value = "topic", defaultValue = "iot/devices/poison-test/DLT") String dltTopic) {
        log.info("[HTTP-DLT] 清空死信主题 [{}]", dltTopic);
        dltScenarioService.purgeDeadLetters(dltTopic);
        return Result.success("已成功彻底清空死信主题 [" + dltTopic + "] 的积压报文");
    }
}
