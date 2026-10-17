package io.github.atengk.boot.sample.service;

import io.github.atengk.boot.core.context.TenantContextHolder;
import io.github.atengk.boot.core.context.UserContextHolder;
import io.github.atengk.boot.sample.entity.UserEntity;
import io.github.atengk.boot.sample.mapper.UserMapper;
import lombok.RequiredArgsConstructor;
import org.slf4j.MDC;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

import java.util.concurrent.CompletableFuture;

/**
 * 异步任务执行示例服务
 * 用于验证受管执行器在 @Async 方法调用下的 MDC 链路追踪标识、用户与多租户四维上下文传播能力
 *
 * @author Ateng
 * @since 2026-10-16
 */
@Service
@RequiredArgsConstructor
public class AsyncSampleService {

    private final UserMapper userMapper;

    /**
     * 异步保存用户实体并抓取执行现场四维上下文
     *
     * @param user 用户实体
     * @return 包含异步线程捕获的 traceId、userId、tenantId、ignoreTenant 与线程名的执行凭据 Future
     */
    @Async
    public CompletableFuture<AsyncExecutionResult> saveUserAsync(UserEntity user) {
        String capturedTraceId = MDC.get("traceId");
        String capturedUserId = UserContextHolder.get();
        String capturedTenantId = TenantContextHolder.getTenantId();
        boolean capturedIgnoreTenant = TenantContextHolder.isIgnoreTenant();

        // 插入数据库，触发 MyBatis-Plus 自动填充（填充 createBy / updateBy）
        userMapper.insert(user);

        AsyncExecutionResult result = new AsyncExecutionResult(
                capturedTraceId,
                capturedUserId,
                capturedTenantId,
                capturedIgnoreTenant,
                user.getId(),
                Thread.currentThread().getName(),
                isCurrentThreadVirtual()
        );
        return CompletableFuture.completedFuture(result);
    }

    /**
     * 兼容 JDK 17 字节码编译期的虚拟线程运行时安全探测
     *
     * @return 当前线程是否为 Java 21+ 虚拟线程
     */
    private static boolean isCurrentThreadVirtual() {
        try {
            return (boolean) Thread.class.getMethod("isVirtual").invoke(Thread.currentThread());
        } catch (Throwable t) {
            return false;
        }
    }

    /**
     * 异步任务执行捕获结果记录
     *
     * @param traceId      异步线程中捕获的 traceId
     * @param userId       异步线程中捕获的用户 ID
     * @param tenantId     异步线程中捕获的租户 ID
     * @param ignoreTenant 异步线程中捕获的忽略租户状态
     * @param entityId     入库实体 ID
     * @param threadName   执行该任务的工作线程名称
     * @param isVirtual    执行该任务的线程是否为虚拟线程
     */
    public record AsyncExecutionResult(
            String traceId,
            String userId,
            String tenantId,
            boolean ignoreTenant,
            Long entityId,
            String threadName,
            boolean isVirtual
    ) {
        /**
         * 兼容旧构造签名
         */
        public AsyncExecutionResult(String traceId, String userId, Long entityId, String threadName, boolean isVirtual) {
            this(traceId, userId, null, false, entityId, threadName, isVirtual);
        }
    }
}
