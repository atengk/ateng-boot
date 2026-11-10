package io.github.atengk.boot.sample.rocketmq.controller;

import io.github.atengk.boot.core.model.Result;
import io.github.atengk.boot.rocketmq.listener.RocketmqListenerEndpoint;
import io.github.atengk.boot.sample.rocketmq.dto.ConsumerEndpointVO;
import io.github.atengk.boot.sample.rocketmq.dto.ContainerControlRequest;
import io.github.atengk.boot.sample.rocketmq.service.ContainerOpsScenarioService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.util.StringUtils;
import org.springframework.web.bind.annotation.*;

import java.util.ArrayList;
import java.util.List;

/**
 * RocketMQ 消费端监听容器运维管控控制器
 *
 * <p>提供端点审计全景、动态削峰暂停/恢复以及生命周期物理启停能力。</p>
 *
 * @author Ateng
 * @since 2026-11-10
 */
@Slf4j
@RestController
@RequestMapping("/sample/rocketmq/container")
@RequiredArgsConstructor
public class RocketmqContainerController {

    private final ContainerOpsScenarioService containerOpsService;

    /**
     * 查询所有已注册消费监听端点的元数据视图列表
     *
     * @return 消费端点元数据列表
     */
    @GetMapping("/endpoints")
    public Result<List<ConsumerEndpointVO>> getEndpoints() {
        List<RocketmqListenerEndpoint> endpoints = containerOpsService.getEndpoints();
        List<ConsumerEndpointVO> vos = new ArrayList<>(endpoints.size());
        for (RocketmqListenerEndpoint ep : endpoints) {
            String filterType = StringUtils.hasText(ep.getSql92()) ? "SQL92" : "TAG";
            String filterExpr = StringUtils.hasText(ep.getSql92()) ? ep.getSql92()
                    : (StringUtils.hasText(ep.getTag()) ? ep.getTag() : "*");
            vos.add(ConsumerEndpointVO.builder()
                    .endpointId(ep.getId())
                    .topic(ep.getTopic())
                    .consumerGroup(ep.getConsumerGroup())
                    .filterType(filterType)
                    .filterExpression(filterExpr)
                    .consumptionThreadCount(ep.getConsumptionThreadCount())
                    .paused(ep.isPaused())
                    .running(ep.isRunning())
                    .build());
        }
        return Result.success(vos);
    }

    /**
     * 动态暂停指定端点削峰（促使新拉取消息返回 FAILURE 触发 Broker 阶梯退避）
     *
     * @param request 控制请求参数
     * @return 操作是否成功
     */
    @PostMapping("/pause")
    public Result<Boolean> pauseEndpoint(@Valid @RequestBody ContainerControlRequest request) {
        boolean success = containerOpsService.pauseEndpoint(request.getEndpointId());
        return Result.success(success);
    }

    /**
     * 恢复指定端点的正常消息消费
     *
     * @param request 控制请求参数
     * @return 操作是否成功
     */
    @PostMapping("/resume")
    public Result<Boolean> resumeEndpoint(@Valid @RequestBody ContainerControlRequest request) {
        boolean success = containerOpsService.resumeEndpoint(request.getEndpointId());
        return Result.success(success);
    }

    /**
     * 物理优雅关闭指定端点的底层 PushConsumer 连接
     *
     * @param request 控制请求参数
     * @return 操作是否成功
     */
    @PostMapping("/stop")
    public Result<Boolean> stopEndpoint(@Valid @RequestBody ContainerControlRequest request) {
        boolean success = containerOpsService.stopEndpoint(request.getEndpointId());
        return Result.success(success);
    }

    /**
     * 重新组装启动指定端点的底层 PushConsumer 连接
     *
     * @param request 控制请求参数
     * @return 操作是否成功
     */
    @PostMapping("/start")
    public Result<Boolean> startEndpoint(@Valid @RequestBody ContainerControlRequest request) {
        boolean success = containerOpsService.startEndpoint(request.getEndpointId());
        return Result.success(success);
    }

    /**
     * 全局暂停所有已注册监听端点
     *
     * @return 成功暂停的端点数量
     */
    @PostMapping("/pause-all")
    public Result<Integer> pauseAll() {
        int count = containerOpsService.pauseAll();
        return Result.success(count);
    }

    /**
     * 全局恢复所有已注册监听端点
     *
     * @return 成功恢复的端点数量
     */
    @PostMapping("/resume-all")
    public Result<Integer> resumeAll() {
        int count = containerOpsService.resumeAll();
        return Result.success(count);
    }
}
