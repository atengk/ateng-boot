package io.github.atengk.boot.ai.graph;

import com.fasterxml.jackson.core.type.TypeReference;
import io.github.atengk.boot.core.util.JsonUtil;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 认知状态图 BaseGraphState 强类型与纯净 JSON 序列化单元测试
 *
 * @author Ateng
 * @since 2026-11-03
 */
class BaseGraphStateSerializationTest {

    record CustomerOrder(String orderId, BigDecimal amount, LocalDateTime createTime, List<String> items) {}

    static class TestGraphState extends BaseGraphState {
        public static final TypedKey<String> USER_ID = TypedKey.of("userId", String.class);
        public static final TypedKey<CustomerOrder> ORDER = TypedKey.of("order", CustomerOrder.class);
        public static final TypedKey<List<String>> TAGS = TypedKey.of("tags", new TypeReference<>() {});

        public TestGraphState(Map<String, Object> initData) {
            super(initData);
        }
    }

    @Test
    @DisplayName("测试强类型读取与基础属性操作")
    void testBasicTypedGetAndSet() {
        TestGraphState state = new TestGraphState(null);
        assertThat(state.has(TestGraphState.USER_ID)).isFalse();

        state.set(TestGraphState.USER_ID, "user-001");
        assertThat(state.has(TestGraphState.USER_ID)).isTrue();
        assertThat(state.get(TestGraphState.USER_ID)).contains("user-001");
        assertThat(state.get(TestGraphState.USER_ID, "default")).isEqualTo("user-001");
        assertThat(state.getOrThrow(TestGraphState.USER_ID)).isEqualTo("user-001");

        state.remove(TestGraphState.USER_ID);
        assertThat(state.has(TestGraphState.USER_ID)).isFalse();
        assertThat(state.get(TestGraphState.USER_ID)).isEmpty();
        assertThat(state.get(TestGraphState.USER_ID, "default")).isEqualTo("default");
        assertThatThrownBy(() -> state.getOrThrow(TestGraphState.USER_ID))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("userId");
    }

    @Test
    @DisplayName("测试反序列化 Map 结构时延迟安全转译，彻底杜绝 ClassCastException")
    void testLazyConversionFromMapToStrongType() {
        // 模拟 JSON 反序列化后，嵌套对象还原为 LinkedHashMap 的典型场景
        Map<String, Object> orderMap = new HashMap<>();
        orderMap.put("orderId", "ORD-12345");
        orderMap.put("amount", new BigDecimal("99.90"));
        orderMap.put("createTime", "2026-11-03 20:00:00");
        orderMap.put("items", List.of("itemA", "itemB"));

        Map<String, Object> rawData = new HashMap<>();
        rawData.put("userId", "user-002");
        rawData.put("order", orderMap);
        rawData.put("tags", List.of("VIP", "HIGH_RISK"));

        TestGraphState state = new TestGraphState(rawData);

        // 强类型安全读取：底层由 JsonUtil 自动无感转换，绝对不抛出 ClassCastException
        Optional<CustomerOrder> orderOpt = state.get(TestGraphState.ORDER);
        assertThat(orderOpt).isPresent();
        CustomerOrder order = orderOpt.get();
        assertThat(order.orderId()).isEqualTo("ORD-12345");
        assertThat(order.amount()).isEqualByComparingTo("99.90");
        assertThat(order.items()).containsExactly("itemA", "itemB");

        // 再次读取：验证直接从缓存读取，依然是目标对象实例
        assertThat(state.get(TestGraphState.ORDER).get()).isSameAs(order);

        // 泛型 TypeReference 读取
        Optional<List<String>> tagsOpt = state.get(TestGraphState.TAGS);
        assertThat(tagsOpt).isPresent();
        assertThat(tagsOpt.get()).containsExactly("VIP", "HIGH_RISK");
    }

    @Test
    @DisplayName("测试纯净 JSON 序列化往返，零 @class 污染")
    void testPureJsonSerializationRoundTrip() {
        CustomerOrder order = new CustomerOrder("ORD-8888", new BigDecimal("128.50"), LocalDateTime.now(), List.of("phone"));
        TestGraphState state = new TestGraphState(Collections.emptyMap());
        state.set(TestGraphState.USER_ID, "user-888");
        state.set(TestGraphState.ORDER, order);
        state.set(TestGraphState.TAGS, List.of("electronic"));

        String json = state.toJson();
        // 断言纯净 JSON，不存在 @class 标识符
        assertThat(json).doesNotContain("@class");
        assertThat(json).contains("ORD-8888");
        assertThat(json).contains("user-888");

        // 反序列化为 Map 并构造新 State
        Map<String, Object> restoredMap = JsonUtil.parseMap(json);
        TestGraphState restoredState = new TestGraphState(restoredMap);

        assertThat(restoredState.get(TestGraphState.USER_ID)).contains("user-888");
        CustomerOrder restoredOrder = restoredState.getOrThrow(TestGraphState.ORDER);
        assertThat(restoredOrder.orderId()).isEqualTo("ORD-8888");
        assertThat(restoredOrder.amount()).isEqualByComparingTo("128.50");
        assertThat(restoredOrder.items()).containsExactly("phone");
    }
}
