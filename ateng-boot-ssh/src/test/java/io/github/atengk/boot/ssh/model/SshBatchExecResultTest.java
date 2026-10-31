package io.github.atengk.boot.ssh.model;

import io.github.atengk.boot.ssh.exception.SshOperationException;
import io.github.atengk.boot.ssh.exception.SshResultCode;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * SshBatchExecResult 与 SshBatchOptions 批量巡检领域模型单元测试
 *
 * @author Ateng
 * @since 2026-10-31
 */
@DisplayName("SshBatchExecResult 与 SshBatchOptions 批量巡检领域模型单元测试")
class SshBatchExecResultTest {

    @Test
    @DisplayName("测试 SshBatchOptions 默认值与工厂构建")
    void testBatchOptions() {
        SshBatchOptions defaults = SshBatchOptions.defaults();
        assertThat(defaults.getConcurrency()).isGreaterThanOrEqualTo(2);
        assertThat(defaults.isFailFast()).isFalse();
        assertThat(defaults.getTimeout()).isNull();
        assertThat(defaults.getExecOptions()).isNull();

        SshBatchOptions customConcurrency = SshBatchOptions.ofConcurrency(5);
        assertThat(customConcurrency.getConcurrency()).isEqualTo(5);
        assertThat(customConcurrency.isFailFast()).isFalse();

        SshBatchOptions failFast = SshBatchOptions.ofFailFast(true);
        assertThat(failFast.isFailFast()).isTrue();

        Duration timeout = Duration.ofSeconds(10);
        SshBatchOptions full = SshBatchOptions.builder()
                .concurrency(8)
                .failFast(true)
                .timeout(timeout)
                .build();
        assertThat(full.getConcurrency()).isEqualTo(8);
        assertThat(full.isFailFast()).isTrue();
        assertThat(full.getTimeout()).isEqualTo(timeout);
    }

    @Test
    @DisplayName("测试 SshBatchExecResult 全量成功聚合语义")
    void testBatchExecResultAllSuccess() {
        SshServerInfo server1 = SshServerInfo.builder().host("10.0.0.1").port(22).username("root").build();
        SshServerInfo server2 = SshServerInfo.builder().host("10.0.0.2").port(22).username("root").build();

        SshExecResult result1 = SshExecResult.builder().exitCode(0).stdout("ok1").build();
        SshExecResult result2 = SshExecResult.builder().exitCode(0).stdout("ok2").build();

        Map<SshServerInfo, SshExecResult> resultMap = new LinkedHashMap<>();
        resultMap.put(server1, result1);
        resultMap.put(server2, result2);

        SshBatchExecResult batchResult = SshBatchExecResult.builder()
                .totalCount(2)
                .successCount(2)
                .failureCount(0)
                .duration(Duration.ofMillis(120))
                .results(resultMap)
                .errors(Collections.emptyMap())
                .build();

        assertThat(batchResult.isAllSuccess()).isTrue();
        assertThat(batchResult.getTotalCount()).isEqualTo(2);
        assertThat(batchResult.getSuccessCount()).isEqualTo(2);
        assertThat(batchResult.getFailureCount()).isEqualTo(0);
        assertThat(batchResult.getDuration()).isEqualTo(Duration.ofMillis(120));
        assertThat(batchResult.getFailedServers()).isEmpty();
        assertThat(batchResult.getSuccessServers()).containsExactlyInAnyOrder(server1, server2);
        assertThat(batchResult.getResult(server1)).isEqualTo(result1);
        assertThat(batchResult.getError(server1)).isNull();

        // 验证命名结果字典
        Map<String, SshExecResult> namedResults = batchResult.getNamedResults();
        assertThat(namedResults).containsEntry("root@10.0.0.1:22", result1);
        assertThat(namedResults).containsEntry("root@10.0.0.2:22", result2);

        // 验证 assertAllSuccess 不抛异常
        assertThat(batchResult.assertAllSuccess()).isSameAs(batchResult);
    }

    @Test
    @DisplayName("测试 SshBatchExecResult 部分失败聚合语义与断言拦截")
    void testBatchExecResultPartialFailure() {
        SshServerInfo server1 = SshServerInfo.builder().host("10.0.0.1").port(22).username("root").build();
        SshServerInfo server2 = SshServerInfo.builder().host("10.0.0.2").port(22).username("root").build();

        SshExecResult result1 = SshExecResult.builder().exitCode(0).stdout("ok1").build();
        SshExecResult result2 = SshExecResult.builder().exitCode(1).stderr("failed").build();
        RuntimeException ex = new RuntimeException("connection timed out");

        Map<SshServerInfo, SshExecResult> resultMap = new LinkedHashMap<>();
        resultMap.put(server1, result1);
        resultMap.put(server2, result2);

        Map<SshServerInfo, Throwable> errorMap = new LinkedHashMap<>();
        errorMap.put(server2, ex);

        SshBatchExecResult batchResult = SshBatchExecResult.builder()
                .totalCount(2)
                .successCount(1)
                .failureCount(1)
                .duration(Duration.ofMillis(200))
                .results(resultMap)
                .errors(errorMap)
                .build();

        assertThat(batchResult.isAllSuccess()).isFalse();
        assertThat(batchResult.getSuccessCount()).isEqualTo(1);
        assertThat(batchResult.getFailureCount()).isEqualTo(1);
        assertThat(batchResult.getFailedServers()).containsExactly(server2);
        assertThat(batchResult.getSuccessServers()).containsExactly(server1);
        assertThat(batchResult.getError(server2)).isEqualTo(ex);

        // 验证 assertAllSuccess 拦截抛出受控业务异常
        assertThatThrownBy(batchResult::assertAllSuccess)
                .isInstanceOf(SshOperationException.class)
                .extracting("resultCode")
                .isEqualTo(SshResultCode.BATCH_EXEC_FAILED);
    }
}
