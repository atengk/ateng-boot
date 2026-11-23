package io.github.atengk.boot.sample.kafka.controller;

import io.github.atengk.boot.core.model.Result;
import io.github.atengk.boot.kafka.model.ConsumerGroupLag;
import io.github.atengk.boot.kafka.model.KafkaContainerStatus;
import io.github.atengk.boot.sample.kafka.dto.ContainerActionRequest;
import io.github.atengk.boot.sample.kafka.dto.ContainerScaleRequest;
import io.github.atengk.boot.sample.kafka.dto.OffsetResetRequest;
import io.github.atengk.boot.sample.kafka.dto.PartitionOffsetResponse;
import io.github.atengk.boot.sample.kafka.service.KafkaOpsScenarioService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.kafka.clients.consumer.OffsetAndMetadata;
import org.apache.kafka.common.TopicPartition;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/**
 * Kafka 运行期位移治理与监听容器动态管控控制器
 *
 * <p>统一暴露消费组已提交位移查询、时间戳与首末端安全回拨重设、实时全分区 Lag 与总积压探测，
 * 以及消费监听容器动态暂停（pause）、恢复（resume）、启动（start）与停止（stop）管控端点。</p>
 *
 * @author Ateng
 * @since 2026-11-09
 */
@Slf4j
@Validated
@RestController
@RequestMapping("/sample/kafka/ops")
@RequiredArgsConstructor
public class KafkaOpsController {

    private final KafkaOpsScenarioService opsService;

    /**
     * 查询消费组当前已提交位移与元数据
     *
     * @param groupId 消费组ID
     * @return 分区位移响应列表
     */
    @GetMapping("/committed-offsets")
    public Result<List<PartitionOffsetResponse>> getCommittedOffsets(
            @RequestParam(value = "groupId", defaultValue = "sample-group") String groupId) {
        log.info("[HTTP-OPS-OFFSETS] 查询消费组位移: groupId={}", groupId);
        Map<TopicPartition, OffsetAndMetadata> offsets = opsService.getConsumerGroupOffsets(groupId);
        List<PartitionOffsetResponse> responses = offsets.entrySet().stream()
                .map(entry -> PartitionOffsetResponse.builder()
                        .topic(entry.getKey().topic())
                        .partition(entry.getKey().partition())
                        .offset(entry.getValue() != null ? entry.getValue().offset() : null)
                        .metadata(entry.getValue() != null ? entry.getValue().metadata() : null)
                        .build())
                .toList();
        return Result.success(responses);
    }

    /**
     * 按指定策略精准安全回拨消费位移
     *
     * @param request 位移回拨请求
     * @return 操作成功结果
     */
    @PostMapping("/reset-offsets")
    public Result<String> resetOffsets(@Valid @RequestBody OffsetResetRequest request) {
        log.info("[HTTP-OPS-RESET-OFFSETS] 重设消费位移: groupId={}, topic={}, type={}",
                request.getGroupId(), request.getTopic(), request.getResetType());
        opsService.resetOffsets(request);
        return Result.success("位移重设成功: " + request.getResetType());
    }

    /**
     * 实时查询消费组指定主题积压 Lag
     *
     * @param groupId 消费组ID
     * @param topic   目标主题名称
     * @return 消费组全分区与总积压 Lag 模型
     */
    @GetMapping("/lag")
    public Result<ConsumerGroupLag> getLag(
            @RequestParam(value = "groupId", defaultValue = "sample-group") String groupId,
            @RequestParam(value = "topic", defaultValue = "sample.order.topic") String topic) {
        log.info("[HTTP-OPS-LAG] 查询消费组积压: groupId={}, topic={}", groupId, topic);
        ConsumerGroupLag lag = opsService.getConsumerGroupLag(groupId, topic);
        return Result.success(lag);
    }

    /**
     * 动态管控消费监听容器运行生命周期
     *
     * @param request 容器管控请求
     * @return 操作结果
     */
    @PostMapping("/container-action")
    public Result<String> containerAction(@Valid @RequestBody ContainerActionRequest request) {
        log.info("[HTTP-OPS-CONTAINER-ACTION] 执行容器管控: idOrTopic={}, action={}",
                request.getContainerIdOrTopic(), request.getAction());
        opsService.executeContainerAction(request);
        return Result.success("容器动作执行成功: " + request.getAction());
    }

    /**
     * 查询全量已注册消费监听容器运行期状态快照列表
     *
     * @return 容器状态快照列表
     */
    @GetMapping("/containers")
    public Result<List<KafkaContainerStatus>> listContainers() {
        List<KafkaContainerStatus> statuses = opsService.getContainerStatuses();
        log.info("[HTTP-OPS-CONTAINERS] 查询全部监听容器快照: count={}", statuses.size());
        return Result.success(statuses);
    }

    /**
     * 查询指定消费监听容器的详细运行状态快照
     *
     * @param idOrTopic 容器 ID 或主题名称
     * @return 容器状态快照视图（未匹配返回 null）
     */
    @GetMapping("/container-status")
    public Result<KafkaContainerStatus> getContainerStatus(
            @NotBlank(message = "idOrTopic 不能为空白")
            @RequestParam(value = "idOrTopic", defaultValue = "sample.order.topic") String idOrTopic) {
        KafkaContainerStatus status = opsService.getContainerStatus(idOrTopic);
        log.info("[HTTP-OPS-CONTAINER-STATUS] 查询容器状态: idOrTopic={}, status={}", idOrTopic, status);
        return Result.success(status);
    }

    /**
     * 运行期动态扩缩指定消费监听容器并发消费者数量
     *
     * @param request 容器并发扩缩请求
     * @return 是否匹配并完成调整
     */
    @PostMapping("/container-scale")
    public Result<Boolean> scaleContainerConcurrency(@Valid @RequestBody ContainerScaleRequest request) {
        log.info("[HTTP-OPS-CONTAINER-SCALE] 动态调整容器并发度: idOrTopic={}, concurrency={}",
                request.getContainerIdOrTopic(), request.getConcurrency());
        boolean scaled = opsService.scaleConcurrency(request.getContainerIdOrTopic(), request.getConcurrency());
        return Result.success(scaled);
    }
}
