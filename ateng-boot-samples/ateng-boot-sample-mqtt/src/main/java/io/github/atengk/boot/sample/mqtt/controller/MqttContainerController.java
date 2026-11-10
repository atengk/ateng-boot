package io.github.atengk.boot.sample.mqtt.controller;

import io.github.atengk.boot.core.model.Result;
import io.github.atengk.boot.mqtt.listener.MqttEndpointStatus;
import io.github.atengk.boot.sample.mqtt.service.MqttContainerGovernanceScenarioService;
import jakarta.validation.constraints.NotBlank;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * 监听消费容器动态运维体验端控制器
 * 提供消费端点动态暂停、恢复、状态探查与流量削峰控制体验
 *
 * @author Ateng
 * @since 2026-11-10
 */
@Slf4j
@Validated
@RestController
@RequestMapping("/sample/mqtt/container")
@RequiredArgsConstructor
public class MqttContainerController {

    private final MqttContainerGovernanceScenarioService governanceScenarioService;

    /**
     * 暂停指定监听消费端点
     *
     * @param endpointId 端点标识（BeanName#methodName 或自定义 id）
     * @return 操作结果提示
     */
    @PostMapping("/pause")
    public Result<String> pauseEndpoint(
            @NotBlank(message = "端点标识 endpointId 不能为空")
            @RequestParam("endpointId") String endpointId) {
        log.info("[HTTP-CONTAINER] 暂停监听端点 [{}]", endpointId);
        governanceScenarioService.pauseEndpoint(endpointId);
        return Result.success("监听端点 [" + endpointId + "] 已成功暂停");
    }

    /**
     * 恢复指定监听消费端点
     *
     * @param endpointId 端点标识
     * @return 操作结果提示
     */
    @PostMapping("/resume")
    public Result<String> resumeEndpoint(
            @NotBlank(message = "端点标识 endpointId 不能为空")
            @RequestParam("endpointId") String endpointId) {
        log.info("[HTTP-CONTAINER] 恢复监听端点 [{}]", endpointId);
        governanceScenarioService.resumeEndpoint(endpointId);
        return Result.success("监听端点 [" + endpointId + "] 已成功恢复");
    }

    /**
     * 查询容器全量端点运行状态列表
     *
     * @return 端点状态列表
     */
    @GetMapping("/endpoints")
    public Result<List<MqttEndpointStatus>> getEndpointStatuses() {
        List<MqttEndpointStatus> statuses = governanceScenarioService.getEndpointStatuses();
        return Result.success(statuses);
    }

    /**
     * 投递测试消息至治理通道
     *
     * @param message 消息内容
     * @return 投递提示
     */
    @PostMapping("/publish-test")
    public Result<String> publishTestMessage(@RequestParam(value = "message", defaultValue = "GOVERNANCE_TEST_MSG") String message) {
        governanceScenarioService.publishMessage(message);
        return Result.success("测试报文已投递至治理通道: " + message);
    }
}
