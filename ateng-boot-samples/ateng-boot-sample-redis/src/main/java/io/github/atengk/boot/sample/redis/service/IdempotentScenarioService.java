package io.github.atengk.boot.sample.redis.service;

import io.github.atengk.boot.core.exception.BusinessException;
import io.github.atengk.boot.redis.idempotent.IdempotentSupplier;
import io.github.atengk.boot.redis.idempotent.RedisIdempotentTemplate;
import io.github.atengk.boot.redis.idempotent.RedisIdempotentUtil;
import io.github.atengk.boot.redis.idempotent.annotation.RedisIdempotent;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 非 Web 业务分布式防重幂等场景服务
 * 演示声明式注解拦截并发防重、业务失败 deleteOnFailure 释放凭据与编程式防重模板
 *
 * @author Ateng
 * @since 2026-10-27
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class IdempotentScenarioService {

    private final RedisIdempotentTemplate redisIdempotentTemplate;

    private final AtomicInteger successProcessCount = new AtomicInteger(0);
    private final AtomicInteger failureProcessCount = new AtomicInteger(0);

    /**
     * 声明式防重处理订单（模拟 MQ 消费或定时任务）
     * 配置 deleteOnFailure = true：业务成功保留锁直至 TTL 超时；业务抛异常时主动删除凭据，允许后续重试
     *
     * @param orderId    订单编号
     * @param shouldFail 是否模拟抛出业务异常
     * @return 执行结果
     */
    @RedisIdempotent(key = "'idemp:order:' + #orderId", expire = "30s", deleteOnFailure = true)
    public String processOrder(String orderId, boolean shouldFail) {
        log.info("[IdempotentScenario] 进入幂等业务执行: orderId={}, shouldFail={}", orderId, shouldFail);
        if (shouldFail) {
            failureProcessCount.incrementAndGet();
            throw new BusinessException("模拟业务处理失败，自动释放幂等凭据以供重试: orderId=" + orderId);
        }
        successProcessCount.incrementAndGet();
        return "SUCCESS_" + orderId;
    }

    /**
     * 编程式防重幂等模板执行
     *
     * @param key      业务键
     * @param timeout  防重超时窗口
     * @param supplier 业务执行体
     * @param <T>      返回类型
     * @param <E>      异常类型
     * @return 业务结果
     * @throws E 业务受控异常
     */
    public <T, E extends Throwable> T executeProgrammatic(String key, Duration timeout, IdempotentSupplier<T, E> supplier) throws E {
        return redisIdempotentTemplate.execute(key, timeout, supplier);
    }

    /**
     * 静态门面防重幂等执行
     *
     * @param key      业务键
     * @param supplier 业务执行体
     * @param <T>      返回类型
     * @param <E>      异常类型
     * @return 业务结果
     * @throws E 业务受控异常
     */
    public <T, E extends Throwable> T executeStatic(String key, IdempotentSupplier<T, E> supplier) throws E {
        return RedisIdempotentUtil.execute(key, supplier);
    }

    public int getSuccessProcessCount() {
        return successProcessCount.get();
    }

    public int getFailureProcessCount() {
        return failureProcessCount.get();
    }

    public void resetCounts() {
        successProcessCount.set(0);
        failureProcessCount.set(0);
    }
}
