package io.github.atengk.boot.sample.rabbitmq.controller;

import io.github.atengk.boot.core.model.Result;
import io.github.atengk.boot.rabbitmq.manager.RabbitContainerManager;
import io.github.atengk.boot.rabbitmq.manager.RabbitContainerStatus;
import io.github.atengk.boot.rabbitmq.manager.RabbitQueueStatus;
import io.github.atengk.boot.sample.rabbitmq.config.RabbitScenarioTopologyConfig;
import io.github.atengk.boot.sample.rabbitmq.dto.ContainerPrefetchRequest;
import io.github.atengk.boot.sample.rabbitmq.dto.ContainerScaleRequest;
import io.github.atengk.boot.sample.rabbitmq.dto.ContainerTargetRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import lombok.RequiredArgsConstructor;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * RabbitMQ 监听容器运行期管控与队列深度观测控制器
 * 演示 RabbitContainerManager 按容器 ID/队列启停、全量启停、并发扩缩容、预取调整及队列积压查询能力
 *
 * @author Ateng
 * @since 2026-11-15
 */
@Validated
@RestController
@RequestMapping("/sample/rabbitmq/container")
@RequiredArgsConstructor
public class RabbitContainerController {

    private final RabbitContainerManager rabbitContainerManager;

    /**
     * 查询全部已注册监听容器运行状态快照列表
     *
     * @return 容器状态快照列表
     */
    @GetMapping("/list")
    public Result<List<RabbitContainerStatus>> listContainers() {
        return Result.success(rabbitContainerManager.getContainerStatuses());
    }

    /**
     * 查询指定容器 ID 或监听队列对应的容器状态快照
     *
     * @param target 容器 ID 或队列名称
     * @return 监听容器状态快照
     */
    @GetMapping("/status")
    public Result<RabbitContainerStatus> getContainerStatus(
            @RequestParam(defaultValue = RabbitScenarioTopologyConfig.QUEUE_ORDER_CREATE)
            @NotBlank(message = "target 不能为空") String target) {
        return Result.success(rabbitContainerManager.getContainerStatus(target));
    }

    /**
     * 启动指定容器 ID 或监听目标队列的全部容器
     *
     * @param request 目标容器请求
     * @return 启动后的容器状态快照
     */
    @PostMapping("/start")
    public Result<RabbitContainerStatus> startContainer(@Valid @RequestBody ContainerTargetRequest request) {
        rabbitContainerManager.start(request.getTarget());
        return Result.success(rabbitContainerManager.getContainerStatus(request.getTarget()), "监听容器启动成功");
    }

    /**
     * 停止指定容器 ID 或监听目标队列的全部容器
     *
     * @param request 目标容器请求
     * @return 停止后的容器状态快照
     */
    @PostMapping("/stop")
    public Result<RabbitContainerStatus> stopContainer(@Valid @RequestBody ContainerTargetRequest request) {
        rabbitContainerManager.stop(request.getTarget());
        return Result.success(rabbitContainerManager.getContainerStatus(request.getTarget()), "监听容器停止成功");
    }

    /**
     * 启动全部监听容器
     *
     * @return 启动后的全部容器状态快照
     */
    @PostMapping("/start-all")
    public Result<List<RabbitContainerStatus>> startAllContainers() {
        rabbitContainerManager.startAll();
        return Result.success(rabbitContainerManager.getContainerStatuses(), "全量监听容器启动成功");
    }

    /**
     * 停止全部监听容器
     *
     * @return 停止后的全部容器状态快照
     */
    @PostMapping("/stop-all")
    public Result<List<RabbitContainerStatus>> stopAllContainers() {
        rabbitContainerManager.stopAll();
        return Result.success(rabbitContainerManager.getContainerStatuses(), "全量监听容器停止成功");
    }

    /**
     * 动态调整目标监听容器并发消费者数量上下限
     *
     * @param request 并发扩缩容请求
     * @return 调整后的容器状态快照
     */
    @PostMapping("/scale")
    public Result<RabbitContainerStatus> scaleConcurrency(@Valid @RequestBody ContainerScaleRequest request) {
        rabbitContainerManager.scaleConcurrency(
                request.getTarget(),
                request.getConcurrentConsumers(),
                request.getMaxConcurrentConsumers()
        );
        return Result.success(rabbitContainerManager.getContainerStatus(request.getTarget()), "容器并发消费者调整成功");
    }

    /**
     * 动态调整目标监听容器通道预取消息数量
     *
     * @param request 预取数调整请求
     * @return 调整后的容器状态快照
     */
    @PostMapping("/prefetch")
    public Result<RabbitContainerStatus> updatePrefetchCount(@Valid @RequestBody ContainerPrefetchRequest request) {
        rabbitContainerManager.updatePrefetchCount(request.getTarget(), request.getPrefetchCount());
        return Result.success(rabbitContainerManager.getContainerStatus(request.getTarget()), "容器预取数调整成功");
    }

    /**
     * 查询指定队列当前消息堆积深度与在线消费者数
     *
     * @param queueName 队列名称
     * @return 队列深度观测快照
     */
    @GetMapping("/queue-status")
    public Result<RabbitQueueStatus> getQueueStatus(
            @RequestParam(defaultValue = RabbitScenarioTopologyConfig.QUEUE_ORDER_CREATE)
            @NotBlank(message = "queueName 不能为空") String queueName) {
        return Result.success(rabbitContainerManager.getQueueStatus(queueName));
    }
}
