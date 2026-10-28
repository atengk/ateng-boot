package io.github.atengk.boot.sample.redis.service;

import io.github.atengk.boot.redis.semaphore.RedisSemaphoreTemplate;
import io.github.atengk.boot.redis.semaphore.RedisSemaphoreUtil;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.Duration;

/**
 * 分布式受管信号量与并发度治理场景服务
 * 演示集群全局并发硬阈值限制、争锁超时优雅降级与动态配额调配
 *
 * @author Ateng
 * @since 2026-10-27
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class SemaphoreThrottlingScenarioService {

    private final RedisSemaphoreTemplate redisSemaphoreTemplate;

    /**
     * 模拟高昂下游接口调用，带并发度治理与超时降级
     *
     * @param serviceName  下游服务名
     * @param totalPermits 总配额
     * @param timeout      等待获取信号量超时时间
     * @return 业务结果或降级结果
     */
    public String callHeavyDownstream(String serviceName, int totalPermits, Duration timeout) {
        String semaphoreKey = "sem:heavy:" + serviceName;
        return redisSemaphoreTemplate.execute(
                semaphoreKey,
                totalPermits,
                1,
                timeout,
                () -> {
                    log.info("[Semaphore] 成功获取信号量许可证，执行高并发下游调用: service={}", serviceName);
                    return "SUCCESS_DOWNSTREAM_" + serviceName;
                },
                () -> {
                    log.warn("[Semaphore] 获取信号量许可证超时，触发兜底降级响应: service={}", serviceName);
                    return "FALLBACK_DOWNSTREAM_" + serviceName;
                }
        );
    }

    /**
     * 基于 RedisSemaphoreUtil 静态门面执行并发度限制
     *
     * @param serviceName  下游服务名
     * @param totalPermits 总配额
     * @param timeout      等待时间
     * @return 业务结果
     */
    public String callWithStaticUtil(String serviceName, int totalPermits, Duration timeout) {
        String semaphoreKey = "sem:static:" + serviceName;
        return RedisSemaphoreUtil.execute(
                semaphoreKey,
                totalPermits,
                timeout,
                () -> "STATIC_SUCCESS_" + serviceName,
                () -> "STATIC_FALLBACK_" + serviceName
        );
    }

    /**
     * 动态设置/重置信号量总许可证配额
     *
     * @param serviceName 下游服务名
     * @param newPermits  新配额
     */
    public boolean resetPermits(String serviceName, int newPermits) {
        String semaphoreKey = "sem:heavy:" + serviceName;
        boolean success = redisSemaphoreTemplate.resetPermits(semaphoreKey, newPermits);
        log.info("[Semaphore] 动态调配信号量许可证: key={}, newPermits={}, success={}", semaphoreKey, newPermits, success);
        return success;
    }

    /**
     * 获取当前可用许可证数量
     */
    public int getAvailablePermits(String serviceName) {
        String semaphoreKey = "sem:heavy:" + serviceName;
        return redisSemaphoreTemplate.availablePermits(semaphoreKey);
    }
}
