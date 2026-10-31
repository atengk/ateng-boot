package io.github.atengk.boot.job.snail.context;

import com.aizuda.snailjob.client.job.core.dto.JobArgs;
import com.aizuda.snailjob.client.job.core.dto.ShardingJobArgs;
import com.aizuda.snailjob.model.dto.ExecuteResult;
import com.fasterxml.jackson.core.type.TypeReference;
import io.github.atengk.boot.core.exception.BusinessException;
import io.github.atengk.boot.job.snail.model.ShardingContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.Serializable;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * {@link SnailJobContext} 与 {@link ShardingContext} 单元测试
 *
 * <p>覆盖强类型工作流上下文读取/转换、增量写回同步、MapReduce 分发阈值防卫与分片取模算法，
 * 100% 离线脱网运行。</p>
 *
 * @author Ateng
 * @since 2026-10-31
 */
class SnailJobContextTest {

    @AfterEach
    void tearDown() {
        SnailJobContextHolder.remove();
        SnailJobContext.setCustomSubtaskDispatcher(null);
    }

    // ==================== 1. ShardingContext 分片测试 ====================

    @Test
    @DisplayName("ShardingContext: 单机分片模式行为判定与匹配")
    void testShardingSingleNode() {
        ShardingContext sharding = new ShardingContext(0, 1);
        assertThat(sharding.isSharded()).isFalse();
        assertThat(sharding.isFirst()).isTrue();
        assertThat(sharding.isLast()).isTrue();

        assertThat(sharding.match(0L)).isTrue();
        assertThat(sharding.match(100L)).isTrue();
        assertThat(sharding.match(-5L)).isTrue();
        assertThat(sharding.match("any-key")).isTrue();
        assertThat(sharding.match((String) null)).isTrue();
    }

    @Test
    @DisplayName("ShardingContext: 分布式多节点分片匹配与非负余数防卫")
    void testShardingDistributedMatching() {
        ShardingContext shard0 = new ShardingContext(0, 3);
        ShardingContext shard1 = new ShardingContext(1, 3);
        ShardingContext shard2 = new ShardingContext(2, 3);

        assertThat(shard0.isSharded()).isTrue();
        assertThat(shard0.isFirst()).isTrue();
        assertThat(shard0.isLast()).isFalse();

        assertThat(shard1.isFirst()).isFalse();
        assertThat(shard1.isLast()).isFalse();

        assertThat(shard2.isFirst()).isFalse();
        assertThat(shard2.isLast()).isTrue();

        // 校验 null 字符串分片键路由至 0 号分片
        assertThat(shard0.match((String) null)).isTrue();
        assertThat(shard1.match((String) null)).isFalse();
        assertThat(shard2.match((String) null)).isFalse();

        // 校验 0, 1, 2 分片互斥归属
        assertThat(shard0.match(0L)).isTrue();
        assertThat(shard1.match(1L)).isTrue();
        assertThat(shard2.match(2L)).isTrue();
        assertThat(shard0.match(3L)).isTrue();

        // 负数主键非负取模防卫
        assertThat(shard2.match(-1L)).isTrue(); // Math.floorMod(-1, 3) == 2
        assertThat(shard1.match(-2L)).isTrue(); // Math.floorMod(-2, 3) == 1
    }

    // ==================== 2. SnailJobContext 工作流上下文强类型操作 ====================

    @Test
    @DisplayName("SnailJobContext: 工作流参数多类型智能转换与反序列化")
    void testWorkflowParamTypeConversions() {
        SnailJobContext context = new SnailJobContext();
        context.appendWfContext("strVal", "hello");
        context.appendWfContext("longVal", 10086L);
        context.appendWfContext("intVal", 42);
        context.appendWfContext("boolVal", true);
        context.appendWfContext("strNum", "999");
        context.appendWfContext("strBool", "true");
        context.appendWfContext("orderJson", "{\"orderId\":98765,\"amount\":199.9}");
        context.appendWfContext("tags", List.of("vip", "paid", "audit"));

        // 基本数据类型
        assertThat(context.getWfParam("strVal", String.class)).isEqualTo("hello");
        assertThat(context.getWfParam("longVal", Long.class)).isEqualTo(10086L);
        assertThat(context.getWfParam("intVal", Integer.class)).isEqualTo(42);
        assertThat(context.getWfParam("boolVal", Boolean.class)).isTrue();

        // 字符串到基本类型自适应互转
        assertThat(context.getWfParam("strNum", Long.class)).isEqualTo(999L);
        assertThat(context.getWfParam("strBool", Boolean.class)).isTrue();

        // 复杂泛型 List 反序列化
        List<String> tags = context.getWfParam("tags", new TypeReference<List<String>>() {});
        assertThat(tags).containsExactly("vip", "paid", "audit");

        // 复杂 DTO 反序列化
        OrderDto order = context.getWfParam("orderJson", OrderDto.class);
        assertThat(order).isNotNull();
        assertThat(order.getOrderId()).isEqualTo(98765L);
        assertThat(order.getAmount()).isEqualTo(199.9);

        // 默认值回退
        assertThat(context.getWfParamOrDefault("nonExist", "default-str")).isEqualTo("default-str");
    }

    @Test
    @DisplayName("SnailJobContext: 变更上下文自动同步到底层 JobArgs")
    void testAppendSyncToJobArgs() {
        JobArgs nativeArgs = new JobArgs();
        SnailJobContext context = new SnailJobContext();
        context.setJobArgs(nativeArgs);

        context.appendWfContext("step1Result", "SUCCESS");
        context.appendWfContext(Map.of("k1", "v1", "k2", 200));

        assertThat(context.getWfParam("step1Result", String.class)).isEqualTo("SUCCESS");
        assertThat(context.getWfParam("k1", String.class)).isEqualTo("v1");
        assertThat(context.getWfParam("k2", Integer.class)).isEqualTo(200);

        // 验证同步到底层 JobArgs
        assertThat(nativeArgs.getChangeWfContext()).containsEntry("step1Result", "SUCCESS");
        assertThat(nativeArgs.getChangeWfContext()).containsEntry("k1", "v1");
        assertThat(nativeArgs.getChangeWfContext()).containsEntry("k2", 200);
    }

    // ==================== 3. MapReduce doMap 分发防线测试 ====================

    @Test
    @DisplayName("doMap: 空任务或空节点名称防卫")
    void testDoMapValidationFailures() {
        SnailJobContext context = new SnailJobContext();

        // 下一任务名为空
        assertThatThrownBy(() -> context.doMap(List.of("task1"), ""))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("MapReduce 下一级任务名称不能为空");

        // 任务名为 ROOT_MAP
        assertThatThrownBy(() -> context.doMap(List.of("task1"), "ROOT_MAP"))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("不能为根任务 ROOT_MAP");

        // 任务列表为空
        assertThatThrownBy(() -> context.doMap(Collections.emptyList(), "processNode"))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("待分发任务列表不能为空");

        // 超过 500 条安全上限
        List<Integer> largeList = new ArrayList<>();
        for (int i = 0; i < 501; i++) {
            largeList.add(i);
        }
        assertThatThrownBy(() -> context.doMap(largeList, "processNode"))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("超过系统安全上限 500 条");
    }

    @Test
    @DisplayName("doMap: 挂载 Mock 分发器正常派发子任务切片")
    void testDoMapWithCustomDispatcher() {
        SnailJobContext context = new SnailJobContext();
        AtomicBoolean dispatched = new AtomicBoolean(false);

        SnailJobContext.setCustomSubtaskDispatcher((taskList, nextTaskName) -> {
            assertThat(nextTaskName).isEqualTo("calculateShardNode");
            assertThat(taskList).hasSize(3);
            assertThat(taskList.get(0)).isEqualTo("chunk1");
            assertThat(taskList.get(1)).isEqualTo("chunk2");
            assertThat(taskList.get(2)).isEqualTo("chunk3");
            dispatched.set(true);
            return ExecuteResult.success();
        });

        ExecuteResult result = context.doMap(List.of("chunk1", "chunk2", "chunk3"), "calculateShardNode");
        assertThat(result).isNotNull();
        assertThat(dispatched.get()).isTrue();
    }

    // ==================== 4. 辅助测试 DTO ====================

    public static class OrderDto implements Serializable {
        private Long orderId;
        private Double amount;

        public Long getOrderId() {
            return orderId;
        }

        public void setOrderId(Long orderId) {
            this.orderId = orderId;
        }

        public Double getAmount() {
            return amount;
        }

        public void setAmount(Double amount) {
            this.amount = amount;
        }
    }
}
