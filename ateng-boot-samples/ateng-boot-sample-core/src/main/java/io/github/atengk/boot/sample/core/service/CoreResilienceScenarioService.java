package io.github.atengk.boot.sample.core.service;

import io.github.atengk.boot.core.audit.OperateLogModel;
import io.github.atengk.boot.core.audit.OperateTypeEnum;
import io.github.atengk.boot.core.code.CommonResultCode;
import io.github.atengk.boot.core.exception.BusinessException;
import io.github.atengk.boot.core.model.PageQuery;
import io.github.atengk.boot.core.model.PageResult;
import io.github.atengk.boot.core.model.Pair;
import io.github.atengk.boot.core.model.Result;
import io.github.atengk.boot.core.model.Triple;
import io.github.atengk.boot.core.util.BusinessAssert;
import io.github.atengk.boot.core.util.IdUtil;
import io.github.atengk.boot.core.util.RetryUtil;
import io.github.atengk.boot.core.util.SpringUtil;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Core 场景四：弹性、发号器、断言与统一模型服务
 * 演示并自验 RetryUtil 指数退避重试、IdUtil 分布式发号器、BusinessAssert、BusinessException 双模堆栈、Result<T>、PageQuery/PageResult 与 OperateLogModel
 *
 * @author Ateng
 * @since 2026-10-19
 */
@Slf4j
@Service
public class CoreResilienceScenarioService {

    /**
     * 1. 验证 RetryUtil 指数退避重试与 Fallback 降级机制
     *
     * @param failAttempts 模拟失败次数
     * @return 最终执行成功结果或兜底降级值
     */
    public String executeWithRetryAndFallback(int failAttempts) {
        AtomicInteger counter = new AtomicInteger(0);

        RetryUtil.RetryConfig config = RetryUtil.RetryConfig.builder()
                .maxAttempts(3)
                .delay(Duration.ofMillis(20))
                .multiplier(1.5)
                .maxDelay(Duration.ofMillis(100))
                .jitter(true)
                .retryPredicate(ex -> ex instanceof IllegalStateException)
                .onRetry((attempt, ex) -> log.warn("RetryUtil 触发第 {} 次重试, 原因: {}", attempt, ex.getMessage()))
                .build();

        return RetryUtil.execute(() -> {
            int current = counter.incrementAndGet();
            if (current <= failAttempts) {
                log.info("模拟临时失败，当前尝试序号: {}", current);
                throw new IllegalStateException("下游服务暂时不可用，尝试序号: " + current);
            }
            log.info("调用成功，尝试序号: {}", current);
            return "SUCCESS_AT_" + current;
        }, config, ex -> {
            log.warn("重试耗尽触发 Fallback 降级兜底: {}", ex.getMessage());
            return "FALLBACK_VALUE";
        });
    }

    /**
     * 1.1 验证 RetryUtil 线性固定间隔重试机制 (multiplier = 1.0, jitter = false)
     *
     * @param failAttempts 模拟失败次数
     * @return 执行成功标识
     */
    public String executeWithLinearRetry(int failAttempts) {
        AtomicInteger counter = new AtomicInteger(0);
        RetryUtil.RetryConfig config = RetryUtil.RetryConfig.builder()
                .maxAttempts(3)
                .delay(Duration.ofMillis(10))
                .multiplier(1.0)
                .jitter(false)
                .retryPredicate(ex -> ex instanceof IllegalStateException)
                .build();

        return RetryUtil.execute(() -> {
            int current = counter.incrementAndGet();
            if (current <= failAttempts) {
                throw new IllegalStateException("线性重试尝试序号: " + current);
            }
            return "LINEAR_SUCCESS_AT_" + current;
        }, config);
    }

    /**
     * 2. 验证 IdUtil 2026 纪元雪花发号器与无锁短 ID
     *
     * @return Triple<Long(雪花ID), String(32位无横线UUID), String(21位NanoId)>
     */
    public Triple<Long, String, String> generateDistributedIds() {
        long snowflakeId = IdUtil.nextId();
        // 显式校验单例 Snowflake 实例与独立指定节点 Snowflake 实例
        long defaultSnowflakeId = IdUtil.getSnowflake().nextId();
        long customSnowflakeId = IdUtil.createSnowflake(1, 1).nextId();
        String simpleUuid = IdUtil.fastSimpleUUID();
        String nanoId = IdUtil.nanoId();

        log.info("IdUtil 发号验证: snowflakeId={}, defaultId={}, customId={}, simpleUuid={}, nanoId={}",
                snowflakeId, defaultSnowflakeId, customSnowflakeId, simpleUuid, nanoId);
        return Triple.of(snowflakeId, simpleUuid, nanoId);
    }

    /**
     * 3. 验证 BusinessAssert 快速失败业务语义断言
     *
     * @param amount 金额
     */
    public void assertBusinessRules(java.math.BigDecimal amount) {
        BusinessAssert.notNull(amount, "金额参数不能为 null");
        BusinessAssert.isTrue(amount.compareTo(java.math.BigDecimal.ZERO) > 0, "金额必须大于0");
        log.info("BusinessAssert 校验通过: amount={}", amount);
    }

    /**
     * 4. 验证 BusinessException 双模堆栈治理机制 (Stack Trace Duality Invariant)
     * - 轻量业务中断 (无 cause): 默认清空堆栈，零抓栈 CPU 开销
     * - 驱动因果转译 (有 cause): 自动采集并保留完整调用栈与原始根因
     *
     * @return Pair<Integer(轻量堆栈深度), Integer(带Cause堆栈深度)>
     */
    public Pair<Integer, Integer> verifyBusinessExceptionStackDuality() {
        BusinessException lightweightEx = new BusinessException(CommonResultCode.BAD_REQUEST);
        int lightweightStackLen = lightweightEx.getStackTrace().length;

        BusinessException causalEx = new BusinessException(
                CommonResultCode.INTERNAL_SERVER_ERROR,
                "驱动调用异常",
                new IllegalStateException("底层连接断开")
        );
        int causalStackLen = causalEx.getStackTrace().length;

        log.info("BusinessException 双模堆栈验证: lightweightLen={}, causalLen={}", lightweightStackLen, causalStackLen);
        return Pair.of(lightweightStackLen, causalStackLen);
    }

    /**
     * 5. 验证 Result<T> 强类型契约、函数式变换与安全取值
     *
     * @param payload 载荷内容
     * @return 处理后的 Result<String>
     */
    public Result<String> processResultContract(String payload) {
        Result<String> rawResult = Result.success(payload);

        // 函数式变换与链式消费
        return rawResult
                .peek(data -> log.info("Result.peek 观察载荷: {}", data))
                .map(data -> "PROCESSED: " + data)
                .ifFail(err -> log.error("Result 失败: [{}] {}", err.getCode(), err.getMsg()));
    }

    /**
     * 6. 验证 PageQuery 分页参数与 PageResult<T> 转换
     *
     * @param current  当前页码
     * @param size     每页条数
     * @return PageResult<String>
     */
    public PageResult<String> processPagination(long current, long size) {
        PageQuery query = new PageQuery(current, size);
        query.addAsc("createTime");

        long queryCurrent = query.getCurrent();
        long querySize = query.getSize();

        List<String> list = List.of("Item-1", "Item-2");
        PageResult<String> pageResult = PageResult.of(list, 100L, queryCurrent, querySize);

        log.info("PageQuery 与 PageResult: current={}, size={}, sorts={}, totalPages={}",
                queryCurrent, querySize, query.getSorts(), pageResult.getPages());
        return pageResult;
    }

    /**
     * 7. 验证 OperateLogModel 操作审计元数据模型构建与序列化
     *
     * @return OperateLogModel 实体
     */
    public OperateLogModel buildAuditLogModel() {
        OperateLogModel model = OperateLogModel.builder()
                .traceId("trace-001")
                .tenantId("tenant-001")
                .userId("user-10086")
                .userName("张三")
                .module("系统管理")
                .operateType(OperateTypeEnum.INSERT)
                .description("新增测试用户")
                .requestMethod("POST")
                .requestUrl("/api/users")
                .clientIp("127.0.0.1")
                .operateLocation("本地测试")
                .requestParams("{\"name\":\"test\"}")
                .responseResult("{\"code\":200}")
                .status(0)
                .costTime(15L)
                .operateTime(LocalDateTime.now())
                .build();

        model.addExtra("env", "dev").addExtra("client", "JUnit5");

        log.info("OperateLogModel 构建成功: isSuccess={}, extraSize={}", model.isSuccess(), model.getExtra().size());
        return model;
    }

    /**
     * 8. 验证 SpringUtil 容器门面
     *
     * @return 当前服务名称或属性
     */
    public String verifySpringUtil() {
        String appName = SpringUtil.getProperty("spring.application.name", "unknown");
        log.info("SpringUtil 读取配置 spring.application.name={}", appName);
        return appName;
    }
}
