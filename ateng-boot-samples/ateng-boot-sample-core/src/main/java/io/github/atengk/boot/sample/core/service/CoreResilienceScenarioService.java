package io.github.atengk.boot.sample.core.service;

import io.github.atengk.boot.core.audit.OperateLogModel;
import io.github.atengk.boot.core.audit.OperateTypeEnum;
import io.github.atengk.boot.core.code.CommonResultCode;
import io.github.atengk.boot.core.code.ResultCodeSegment;
import io.github.atengk.boot.core.code.ResultCodeUtil;
import io.github.atengk.boot.core.concurrent.SlidingWindowCounter;
import io.github.atengk.boot.core.exception.BusinessException;
import io.github.atengk.boot.core.id.SnowflakeIdInfo;
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

    /**
     * 9. 验证雪花 ID 逆向反解与 Base62 短码无损互转
     *
     * @param id 雪花算法生成的长整型 ID
     * @return Pair<SnowflakeIdInfo(逆向反解信息), String(Base62短码)>
     */
    public Pair<SnowflakeIdInfo, String> parseSnowflakeAndBase62(long id) {
        SnowflakeIdInfo info = IdUtil.parseSnowflake(id);
        String base62 = IdUtil.toBase62(id);
        long restored = IdUtil.fromBase62(base62);

        log.info("Snowflake 逆向反解与 Base62: id={}, datacenter={}, worker={}, seq={}, base62={}, restoredMatch={}",
                id, info.datacenterId(), info.workerId(), info.sequence(), base62, id == restored);
        return Pair.of(info, base62);
    }

    /**
     * 10. 验证 SlidingWindowCounter 进程内无外部依赖高性能滑动窗口流控
     *
     * @return Pair<Long(窗口内累计计数), Boolean(尝试获取许可结果)>
     */
    public Pair<Long, Boolean> demonstrateSlidingWindowCounter() {
        SlidingWindowCounter counter = new SlidingWindowCounter(Duration.ofSeconds(1), 5);
        long count1 = counter.incrementAndGet();
        long count2 = counter.incrementAndGet(2);
        boolean acquired = counter.tryAcquire(1, 5);
        boolean rejected = counter.tryAcquire(10, 5);

        log.info("SlidingWindowCounter 滑动窗口限流: count1={}, count2={}, acquired={}, rejected={}",
                count1, count2, acquired, !rejected);
        return Pair.of(counter.getCount(), acquired);
    }

    /**
     * 11. 验证 BusinessAssert 惰性占位符格式化与数值区间断言
     *
     * @param userId  用户ID
     * @param balance 账户余额
     */
    public void assertBusinessRulesExtended(String userId, java.math.BigDecimal balance) {
        BusinessAssert.hasText(userId, "用户ID不能为空");
        BusinessAssert.gt(balance, java.math.BigDecimal.ZERO, "用户 [{}] 账户余额 [{}] 必须大于 0", userId, balance);
        BusinessAssert.between(balance, java.math.BigDecimal.ONE, new java.math.BigDecimal("1000000"), "余额超出安全范围");
        log.info("BusinessAssert 扩展断言通过: userId={}, balance={}", userId, balance);
    }

    /**
     * 12. 验证 PageResult 导航辅助与空判断
     *
     * @return 分页结果实例
     */
    public PageResult<String> demonstratePageResultNavigation() {
        PageResult<String> pageResult = PageResult.of(List.of("Data-A", "Data-B"), 100L, 2L, 10L);
        log.info("PageResult 导航属性: hasPrevious={}, hasNext={}, isEmpty={}",
                pageResult.hasPrevious(), pageResult.hasNext(), pageResult.isEmpty());
        return pageResult;
    }

    /**
     * 场景演示用业务领域错误码枚举（规范落在千位划分的各领域号段内）
     */
    @lombok.Getter
    @lombok.AllArgsConstructor
    public enum SampleBusinessResultCode implements io.github.atengk.boot.core.code.IResultCode {
        USER_NOT_FOUND(1001, "核心底座: 用户记录未找到"),
        TOKEN_INVALID(2001, "认证鉴权: 令牌无效"),
        FILE_TOO_LARGE(3001, "文件存储: 超出大小限制");

        private final Integer code;
        private final String msg;
    }

    /**
     * 13. 验证 ResultCodeSegment 号段归属、区间匹配与 ResultCodeUtil 错误码唯一性断言
     *
     * @return 错误码治理分析结果 Map
     */
    public java.util.Map<String, Object> demonstrateResultCodeGovernance() {
        java.util.Map<String, Object> results = new java.util.HashMap<>();

        // 1. 规约号段探测
        ResultCodeSegment coreSegment = ResultCodeUtil.getSegment(SampleBusinessResultCode.USER_NOT_FOUND);
        results.put("coreSegment", coreSegment != null ? coreSegment.name() : null);

        // 2. 号段区间合规性防卫断言与 isInSegment 判定
        ResultCodeUtil.assertInSegment(SampleBusinessResultCode.USER_NOT_FOUND, ResultCodeSegment.SYSTEM_CORE);
        boolean isAuthSecurity = ResultCodeUtil.isInSegment(SampleBusinessResultCode.TOKEN_INVALID, ResultCodeSegment.AUTH_SECURITY);
        results.put("isAuthSecurity", isAuthSecurity);

        // 3. 规约断言：验证状态码数组无重复编号碰撞
        ResultCodeUtil.assertUnique(SampleBusinessResultCode.values());
        results.put("uniqueAssertPassed", true);

        log.info("ResultCode 治理分析结果: {}", results);
        return results;
    }
}
