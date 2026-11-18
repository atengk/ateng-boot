package io.github.atengk.boot.sample.mqtt.controller;

import io.github.atengk.boot.core.model.Result;
import io.github.atengk.boot.sample.mqtt.dto.ClientPublishRequest;
import io.github.atengk.boot.sample.mqtt.dto.MqttClientSummaryVO;
import io.github.atengk.boot.sample.mqtt.dto.RegisterClientRequest;
import io.github.atengk.boot.sample.mqtt.service.ClientGovernanceScenarioService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 命名多客户端动态注册与定向绑定发信治理控制器
 * 提供动态客户端注册、基于 MqttTemplate.use(clientName) 的定向发布、健康快照查询及安全注销入口
 *
 * @author Ateng
 * @since 2026-11-18
 */
@Slf4j
@Validated
@RestController
@RequestMapping("/sample/mqtt/clients")
@RequiredArgsConstructor
public class MqttClientGovernanceController {

    private final ClientGovernanceScenarioService clientGovernanceScenarioService;

    /**
     * 运行期动态注册并连接新的命名 MQTT 客户端
     *
     * @param request 动态客户端注册参数
     * @return 注册成功后的客户端状态快照
     */
    @PostMapping("/register")
    public Result<MqttClientSummaryVO> registerClient(@Valid @RequestBody RegisterClientRequest request) {
        log.info("[HTTP-CLIENTS] 注册命名客户端: clientName={}", request.getClientName());
        MqttClientSummaryVO summary = clientGovernanceScenarioService.registerClient(request);
        return Result.success(summary);
    }

    /**
     * 通过 MqttTemplate.use(clientName) 绑定视图向指定客户端发布报文
     *
     * @param request 定向发布请求参数
     * @return 发布成功提示
     */
    @PostMapping("/publish")
    public Result<String> publishToClient(@Valid @RequestBody ClientPublishRequest request) {
        log.info("[HTTP-CLIENTS] 定向发信: clientName={}, topic={}, qos={}",
                request.getClientName(), request.getTopic(), request.getQos());
        int qos = request.getQos() != null ? request.getQos() : 1;
        boolean retained = Boolean.TRUE.equals(request.getRetained());
        clientGovernanceScenarioService.publishToClient(
                request.getClientName(),
                request.getTopic(),
                request.getPayload(),
                qos,
                retained
        );
        return Result.success("已通过客户端 [" + request.getClientName() + "] 定向投递报文至主题 [" + request.getTopic() + "]");
    }

    /**
     * 查询所有受管命名客户端的状态快照清单
     *
     * @return 客户端快照列表
     */
    @GetMapping
    public Result<List<MqttClientSummaryVO>> listClients() {
        return Result.success(clientGovernanceScenarioService.listClientSummaries());
    }

    /**
     * 查询指定动态客户端收到的演示报文列表
     *
     * @param clientName 客户端逻辑名称
     * @return 已消费载荷列表
     */
    @GetMapping("/consumed")
    public Result<List<String>> getConsumedMessages(
            @NotBlank(message = "clientName 不能为空")
            @RequestParam("clientName") String clientName) {
        return Result.success(clientGovernanceScenarioService.getConsumedMessages(clientName));
    }

    /**
     * 安全注销指定动态命名客户端并释放关联度量资源
     *
     * @param clientName 客户端逻辑名称
     * @param force      是否强制注销
     * @return 注销结果提示
     */
    @DeleteMapping("/{clientName}")
    public Result<String> unregisterClient(
            @NotBlank(message = "clientName 不能为空") @PathVariable("clientName") String clientName,
            @RequestParam(value = "force", defaultValue = "false") boolean force) {
        log.info("[HTTP-CLIENTS] 注销命名客户端: clientName={}, force={}", clientName, force);
        clientGovernanceScenarioService.unregisterClient(clientName, force);
        return Result.success("已成功安全注销动态客户端 [" + clientName + "]");
    }
}
