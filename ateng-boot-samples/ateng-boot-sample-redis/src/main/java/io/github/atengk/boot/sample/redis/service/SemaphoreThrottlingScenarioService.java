package io.github.atengk.boot.sample.redis.service;

import io.github.atengk.boot.redis.semaphore.RedisSemaphoreTemplate;
import io.github.atengk.boot.redis.semaphore.RedisSemaphoreUtil;
import io.github.atengk.boot.sample.redis.model.traffic.CreditCheckRequest;
import io.github.atengk.boot.sample.redis.model.traffic.CreditCheckVO;
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
     * 外部征信评估在途并发槽位管控（带超时降级）
     *
     * @param request 征信评估请求
     * @return 征信评估结果 VO
     */
    public CreditCheckVO checkCredit(CreditCheckRequest request) {
        String semaphoreKey = "sem:credit:eval";
        String masked = maskIdCard(request.getIdCard());
        return redisSemaphoreTemplate.execute(
                semaphoreKey,
                10,
                1,
                Duration.ofMillis(500),
                () -> {
                    log.info("[SemaphoreThrottling] 成功获取信号量槽位，执行征信评估: customerName={}, idCard={}",
                            request.getCustomerName(), masked);
                    return CreditCheckVO.builder()
                            .idCard(masked)
                            .customerName(request.getCustomerName())
                            .creditScore(750)
                            .fallback(false)
                            .message("征信评估成功，信用等级极佳")
                            .build();
                },
                () -> {
                    log.warn("[SemaphoreThrottling] 获取信号量槽位超时，触发兜底排队降级: customerName={}", request.getCustomerName());
                    return CreditCheckVO.builder()
                            .idCard(masked)
                            .customerName(request.getCustomerName())
                            .creditScore(null)
                            .fallback(true)
                            .message("征信下游通道在途并发饱和，已触发弹性降级保护")
                            .build();
                }
        );
    }

    /**
     * 基于 RedisSemaphoreUtil 静态门面的外部征信评估
     *
     * @param request 征信评估请求
     * @return 征信评估结果 VO
     */
    public CreditCheckVO checkCreditStatic(CreditCheckRequest request) {
        String semaphoreKey = "sem:static:eval";
        String masked = maskIdCard(request.getIdCard());
        return RedisSemaphoreUtil.execute(
                semaphoreKey,
                10,
                Duration.ofMillis(500),
                () -> {
                    log.info("[SemaphoreThrottling] 静态门面获取信号量成功: customerName={}, idCard={}",
                            request.getCustomerName(), masked);
                    return CreditCheckVO.builder()
                            .idCard(masked)
                            .customerName(request.getCustomerName())
                            .creditScore(780)
                            .fallback(false)
                            .message("静态门面征信评估成功")
                            .build();
                },
                () -> {
                    log.warn("[SemaphoreThrottling] 静态门面获取信号量超时降级: customerName={}", request.getCustomerName());
                    return CreditCheckVO.builder()
                            .idCard(masked)
                            .customerName(request.getCustomerName())
                            .creditScore(null)
                            .fallback(true)
                            .message("静态门面并发槽位繁忙，已触发弹性降级")
                            .build();
                }
        );
    }

    /**
     * 身份证脱敏清洗辅助方法
     */
    private String maskIdCard(String idCard) {
        if (idCard == null || idCard.length() < 8) {
            return "***";
        }
        return idCard.substring(0, 3) + "******" + idCard.substring(idCard.length() - 4);
    }

    /**
     * 模拟高昂下游接口调用，带并发度治理与超时降级（兼容存量单测）
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
     * 基于 RedisSemaphoreUtil 静态门面执行并发度限制（兼容存量单测）
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
        String semaphoreKey = serviceName.startsWith("sem:") ? serviceName : "sem:heavy:" + serviceName;
        boolean success = redisSemaphoreTemplate.resetPermits(semaphoreKey, newPermits);
        log.info("[Semaphore] 动态调配信号量许可证: key={}, newPermits={}, success={}", semaphoreKey, newPermits, success);
        return success;
    }

    /**
     * 获取当前可用许可证数量
     */
    public int getAvailablePermits(String serviceName) {
        String semaphoreKey = serviceName.startsWith("sem:") ? serviceName : "sem:heavy:" + serviceName;
        return redisSemaphoreTemplate.availablePermits(semaphoreKey);
    }
}
