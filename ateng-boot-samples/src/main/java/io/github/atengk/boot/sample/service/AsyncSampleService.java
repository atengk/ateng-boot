package io.github.atengk.boot.sample.service;

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
 * 用于验证受管线程池在 @Async 方法调用下的 MDC 链路追踪标识与用户身份上下文传播能力
 *
 * @author Ateng
 * @since 2026-10-16
 */
@Service
@RequiredArgsConstructor
public class AsyncSampleService {

    private final UserMapper userMapper;

    /**
     * 异步保存用户实体并抓取执行现场上下文
     *
     * @param user 用户实体
     * @return 包含异步线程捕获的 traceId、userId 与线程名的执行凭据 Future
     */
    @Async
    public CompletableFuture<AsyncExecutionResult> saveUserAsync(UserEntity user) {
        String capturedTraceId = MDC.get("traceId");
        String capturedUserId = UserContextHolder.get();

        // 插入数据库，触发 MyBatis-Plus 自动填充（填充 createBy / updateBy）
        userMapper.insert(user);

        AsyncExecutionResult result = new AsyncExecutionResult(
                capturedTraceId,
                capturedUserId,
                user.getId(),
                Thread.currentThread().getName()
        );
        return CompletableFuture.completedFuture(result);
    }

    /**
     * 异步任务执行捕获结果记录
     *
     * @param traceId    异步线程中捕获的 traceId
     * @param userId     异步线程中捕获的用户 ID
     * @param entityId   入库实体 ID
     * @param threadName 执行该任务的工作线程名称
     */
    public record AsyncExecutionResult(
            String traceId,
            String userId,
            Long entityId,
            String threadName
    ) {
    }
}
