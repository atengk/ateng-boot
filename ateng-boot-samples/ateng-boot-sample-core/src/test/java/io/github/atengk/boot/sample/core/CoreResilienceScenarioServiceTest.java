package io.github.atengk.boot.sample.core;

import io.github.atengk.boot.core.audit.OperateLogModel;
import io.github.atengk.boot.core.audit.OperateTypeEnum;
import io.github.atengk.boot.core.exception.BusinessException;
import io.github.atengk.boot.core.id.SnowflakeIdInfo;
import io.github.atengk.boot.core.model.PageResult;
import io.github.atengk.boot.core.model.Pair;
import io.github.atengk.boot.core.model.Result;
import io.github.atengk.boot.core.model.Triple;
import io.github.atengk.boot.core.util.IdUtil;
import io.github.atengk.boot.sample.core.service.CoreResilienceScenarioService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Core 弹性、发号器、断言与统一模型端到端集成测试
 *
 * @author Ateng
 * @since 2026-10-19
 */
@SpringBootTest
class CoreResilienceScenarioServiceTest {

    @Autowired
    private CoreResilienceScenarioService resilienceScenarioService;

    @Test
    @DisplayName("测试RetryUtil指数退避重试与成功恢复")
    void testRetrySuccess() {
        // 模拟前 1 次失败，第 2 次重试成功
        String result = resilienceScenarioService.executeWithRetryAndFallback(1);
        assertThat(result).isEqualTo("SUCCESS_AT_2");
    }

    @Test
    @DisplayName("测试RetryUtil耗尽尝试触发Fallback降级")
    void testRetryExhaustedFallback() {
        // 模拟连续失败 5 次，超出 maxAttempts(3)
        String result = resilienceScenarioService.executeWithRetryAndFallback(5);
        assertThat(result).isEqualTo("FALLBACK_VALUE");
    }

    @Test
    @DisplayName("测试RetryUtil线性固定间隔重试")
    void testLinearRetrySuccess() {
        // 模拟前 1 次失败，第 2 次线性重试成功
        String result = resilienceScenarioService.executeWithLinearRetry(1);
        assertThat(result).isEqualTo("LINEAR_SUCCESS_AT_2");

        // 模拟连续失败 3 次耗尽重试抛出异常
        assertThatThrownBy(() -> resilienceScenarioService.executeWithLinearRetry(3))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("线性重试尝试序号: 3");
    }

    @Test
    @DisplayName("测试IdUtil分布式雪花发号器与无锁短ID")
    void testGenerateDistributedIds() {
        Triple<Long, String, String> ids = resilienceScenarioService.generateDistributedIds();

        // 64 位雪花正整数
        assertThat(ids.left()).isPositive();
        // 32 位无横线 UUID
        assertThat(ids.middle()).hasSize(32).doesNotContain("-");
        // 21 位 NanoId
        assertThat(ids.right()).hasSize(21);
    }

    @Test
    @DisplayName("测试BusinessAssert业务断言快速失败")
    void testAssertBusinessRules() {
        // 合法参数
        resilienceScenarioService.assertBusinessRules(new BigDecimal("10.5"));

        // 非法参数触发断言失败抛出 BusinessException
        assertThatThrownBy(() -> resilienceScenarioService.assertBusinessRules(new BigDecimal("-1.0")))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("金额必须大于0");

        assertThatThrownBy(() -> resilienceScenarioService.assertBusinessRules(null))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("金额参数不能为 null");
    }

    @Test
    @DisplayName("测试BusinessException双模堆栈治理机制")
    void testVerifyBusinessExceptionStackDuality() {
        Pair<Integer, Integer> pair = resilienceScenarioService.verifyBusinessExceptionStackDuality();
        // 轻量异常：清空堆栈，深度为 0，零抓栈开销
        assertThat(pair.left()).isZero();
        // 根因因果异常：自动保留当前完整调用栈，深度 > 0
        assertThat(pair.right()).isPositive();
    }

    @Test
    @DisplayName("测试Result<T>响应契约与函数式变换")
    void testProcessResultContract() {
        Result<String> result = resilienceScenarioService.processResultContract("order_123");

        assertThat(result.isSuccess()).isTrue();
        assertThat(result.getCode()).isEqualTo(200);
        assertThat(result.getData()).isEqualTo("PROCESSED: order_123");
        assertThat(result.getOrThrow()).isEqualTo("PROCESSED: order_123");
    }

    @Test
    @DisplayName("测试PageQuery分页参数与PageResult封装")
    void testProcessPagination() {
        PageResult<String> pageResult = resilienceScenarioService.processPagination(2, 20);

        assertThat(pageResult.getCurrent()).isEqualTo(2L);
        assertThat(pageResult.getSize()).isEqualTo(20L);
        assertThat(pageResult.getTotal()).isEqualTo(100L);
        assertThat(pageResult.getPages()).isEqualTo(5L);
        assertThat(pageResult.getRecords()).hasSize(2);
    }

    @Test
    @DisplayName("测试OperateLogModel操作审计日志模型")
    void testBuildAuditLogModel() {
        OperateLogModel model = resilienceScenarioService.buildAuditLogModel();

        assertThat(model.isSuccess()).isTrue();
        assertThat(model.getOperateType()).isEqualTo(OperateTypeEnum.INSERT);
        assertThat(model.getExtra())
                .containsEntry("env", "dev")
                .containsEntry("client", "JUnit5");
    }

    @Test
    @DisplayName("测试SpringUtil容器门面读取配置与环境")
    void testVerifySpringUtil() {
        String appName = resilienceScenarioService.verifySpringUtil();
        assertThat(appName).isEqualTo("ateng-boot-sample-core");
    }

    @Test
    @DisplayName("测试Snowflake逆向反解与Base62短码互转")
    void testParseSnowflakeAndBase62() {
        long snowflakeId = IdUtil.nextId();
        Pair<SnowflakeIdInfo, String> pair = resilienceScenarioService.parseSnowflakeAndBase62(snowflakeId);

        SnowflakeIdInfo info = pair.left();
        String base62 = pair.right();

        assertThat(info.id()).isEqualTo(snowflakeId);
        assertThat(info.timestamp()).isPositive();
        assertThat(info.dateTime()).isNotNull();
        assertThat(base62).isNotBlank();
        assertThat(IdUtil.fromBase62(base62)).isEqualTo(snowflakeId);
    }

    @Test
    @DisplayName("测试SlidingWindowCounter内存平滑滑动窗口限流")
    void testSlidingWindowCounter() {
        Pair<Long, Boolean> result = resilienceScenarioService.demonstrateSlidingWindowCounter();
        assertThat(result.left()).isEqualTo(4L);
        assertThat(result.right()).isTrue();
    }

    @Test
    @DisplayName("测试BusinessAssert扩展断言与占位符格式化")
    void testAssertBusinessRulesExtended() {
        // 成功分支
        resilienceScenarioService.assertBusinessRulesExtended("user-001", new BigDecimal("100.50"));

        // 占位符格式化失败断言
        assertThatThrownBy(() -> resilienceScenarioService.assertBusinessRulesExtended("user-001", BigDecimal.ZERO))
                .isInstanceOf(BusinessException.class)
                .hasMessage("用户 [user-001] 账户余额 [0] 必须大于 0");

        // 区间失败断言
        assertThatThrownBy(() -> resilienceScenarioService.assertBusinessRulesExtended("user-001", new BigDecimal("20000000")))
                .isInstanceOf(BusinessException.class)
                .hasMessage("余额超出安全范围");
    }

    @Test
    @DisplayName("测试PageResult导航判断")
    void testPageResultNavigation() {
        PageResult<String> pageResult = resilienceScenarioService.demonstratePageResultNavigation();
        assertThat(pageResult.hasPrevious()).isTrue();
        assertThat(pageResult.hasNext()).isTrue();
        assertThat(pageResult.isEmpty()).isFalse();

        PageResult<Object> empty = PageResult.empty();
        assertThat(empty.hasPrevious()).isFalse();
        assertThat(empty.hasNext()).isFalse();
        assertThat(empty.isEmpty()).isTrue();
    }
}
