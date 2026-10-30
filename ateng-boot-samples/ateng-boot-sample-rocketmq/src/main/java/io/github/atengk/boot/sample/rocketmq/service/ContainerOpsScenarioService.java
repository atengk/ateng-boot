package io.github.atengk.boot.sample.rocketmq.service;

import io.github.atengk.boot.rocketmq.container.RocketmqContainerManager;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

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
