package io.github.atengk.boot.sample.rocketmq.service;

import io.github.atengk.boot.rocketmq.container.RocketmqContainerManager;
import io.github.atengk.boot.rocketmq.listener.RocketmqListenerEndpoint;
import io.github.atengk.boot.rocketmq.model.RocketmqContainerStatus;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Set;

/**
 * 运行期消费监听容器运维管控场景业务服务
 *
 * <p>演示端点审计可见性、动态削峰流控（pause/resume）以及生命周期物理启停（stop/start）。</p>
 *
 * @author Ateng
 * @since 2026-10-30
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ContainerOpsScenarioService {

    private final RocketmqContainerManager containerManager;

    public Set<String> getEndpointIds() {
        return containerManager.getEndpointIds();
    }

    public List<RocketmqListenerEndpoint> getEndpoints() {
        return containerManager.getEndpoints();
    }

    /**
     * 获取所有已注册监听容器的只读不可变运行期状态快照列表
     *
     * @return 不可变容器状态快照列表
     */
    public List<RocketmqContainerStatus> getContainerStatuses() {
        return containerManager.getContainerStatuses();
    }

    /**
     * 精确查询单个监听容器的只读不可变运行期状态快照
     *
     * @param endpointId 监听端点唯一标识
     * @return 容器状态快照，未命中返回 null
     */
    public RocketmqContainerStatus getContainerStatus(String endpointId) {
        return containerManager.getContainerStatus(endpointId);
    }

    public boolean pauseEndpoint(String endpointId) {
        log.info("[OPS-SCENARIO] 暂停指定监听端点: endpointId={}", endpointId);
        return containerManager.pause(endpointId);
    }

    public boolean resumeEndpoint(String endpointId) {
        log.info("[OPS-SCENARIO] 恢复指定监听端点: endpointId={}", endpointId);
        return containerManager.resume(endpointId);
    }

    public boolean isPaused(String endpointId) {
        return containerManager.isPaused(endpointId);
    }

    public boolean stopEndpoint(String endpointId) {
        log.info("[OPS-SCENARIO] 停止指定监听端点: endpointId={}", endpointId);
        return containerManager.stop(endpointId);
    }

    public boolean startEndpoint(String endpointId) {
        log.info("[OPS-SCENARIO] 启动指定监听端点: endpointId={}", endpointId);
        return containerManager.start(endpointId);
    }

    public boolean isRunning(String endpointId) {
        return containerManager.isRunning(endpointId);
    }

    public int pauseAll() {
        return containerManager.pauseAll();
    }

    public int resumeAll() {
        return containerManager.resumeAll();
    }
}
