package io.github.atengk.boot.core.util;

import com.fasterxml.jackson.core.type.TypeReference;
import io.github.atengk.boot.core.annotation.FieldSensitive;
import io.github.atengk.boot.core.enums.SensitiveType;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.Serializable;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/**
 * {@link JsonUtil#deepClone(Object)} 深度克隆门面对抗性单元测试
 *
 * @author Ateng
 * @since 2026-10-27
 */
@DisplayName("JsonUtil 深度克隆门面测试")
class JsonUtilDeepCloneTest {

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    static class OrderContext {
        private Long orderId;
        private String orderNo;
        private LocalDateTime createTime;
        private UserInfo user;
        private List<OrderItem> items;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    static class UserInfo {
        private Long userId;
        private String username;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    static class OrderItem {
        private String skuCode;
        private Integer quantity;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    static class SensitiveUser {
        private Long id;
        @FieldSensitive(SensitiveType.MOBILE_PHONE)
        private String mobile;
        @FieldSensitive(SensitiveType.ID_CARD)
        private String idCard;
    }

    @Data
    static class CyclicNode {
        private String name;
        private CyclicNode next;
    }

    @Test
    @DisplayName("应该支持基本对象与嵌套对象的纯内存无损深拷贝且隔离内存引用")
    void shouldDeepCloneNestedObjectSuccessfully() {
        LocalDateTime now = LocalDateTime.of(2026, 10, 27, 8, 30, 0);
        List<OrderItem> items = new ArrayList<>();
        items.add(OrderItem.builder().skuCode("SKU-001").quantity(2).build());

        OrderContext original = OrderContext.builder()
                .orderId(1001L)
                .orderNo("ORD-20261027-001")
                .createTime(now)
                .user(UserInfo.builder().userId(88L).username("张三").build())
                .items(items)
                .build();

        OrderContext cloned = JsonUtil.deepClone(original);

        assertNotNull(cloned);
        assertNotSame(original, cloned);
        assertEquals(original.getOrderId(), cloned.getOrderId());
        assertEquals(original.getOrderNo(), cloned.getOrderNo());
        assertEquals(now, cloned.getCreateTime());

        // 验证嵌套对象与集合的内存引用绝对隔离
        assertNotSame(original.getUser(), cloned.getUser());
        assertEquals(original.getUser().getUsername(), cloned.getUser().getUsername());
        assertNotSame(original.getItems(), cloned.getItems());
        assertEquals(1, cloned.getItems().size());
        assertNotSame(original.getItems().get(0), cloned.getItems().get(0));

        // 修改克隆对象，原始对象完全不受影响（防并发脏写）
        cloned.getUser().setUsername("李四");
        cloned.getItems().get(0).setQuantity(99);
        cloned.getItems().add(OrderItem.builder().skuCode("SKU-002").quantity(5).build());

        assertEquals("张三", original.getUser().getUsername());
        assertEquals(2, original.getItems().get(0).getQuantity());
        assertEquals(1, original.getItems().size());
    }

    @Test
    @DisplayName("应该支持泛型复杂结构通过 TypeReference 深度克隆")
    void shouldDeepCloneWithGenericTypeReference() {
        List<UserInfo> originalList = new ArrayList<>();
        originalList.add(UserInfo.builder().userId(1L).username("User-1").build());
        originalList.add(UserInfo.builder().userId(2L).username("User-2").build());

        List<UserInfo> clonedList = JsonUtil.deepClone(originalList, new TypeReference<List<UserInfo>>() {});

        assertNotNull(clonedList);
        assertNotSame(originalList, clonedList);
        assertEquals(2, clonedList.size());
        assertNotSame(originalList.get(0), clonedList.get(0));
        assertEquals("User-1", clonedList.get(0).getUsername());

        clonedList.get(0).setUsername("Mutated");
        assertEquals("User-1", originalList.get(0).getUsername());
    }

    @Test
    @DisplayName("空值安全边界测试：传入 null 应该保底返回 null")
    void shouldReturnNullWhenSourceIsNull() {
        assertNull(JsonUtil.deepClone(null));
        assertNull(JsonUtil.deepClone(null, new TypeReference<Map<String, Object>>() {}));
    }

    @Test
    @DisplayName("ADR-0012 内存真明文守护测试：深度克隆必须抑制 @FieldSensitive 脱敏注解，保持原始真实明文")
    void shouldSuppressFieldSensitiveAndPreservePlaintext() {
        SensitiveUser original = SensitiveUser.builder()
                .id(1L)
                .mobile("13812345678")
                .idCard("110101199003072345")
                .build();

        // 正常序列化为 JSON 时应触发脱敏
        String json = JsonUtil.toJsonString(original);
        assertTrue(json.contains("138****5678"));

        // deepClone 必须压制脱敏，保持 100% 原始真实明文
        SensitiveUser cloned = JsonUtil.deepClone(original);
        assertNotNull(cloned);
        assertNotSame(original, cloned);
        assertEquals("13812345678", cloned.getMobile());
        assertEquals("110101199003072345", cloned.getIdCard());
    }

    @Test
    @DisplayName("应该支持直接克隆集合容器、不可变集合并保持泛型元素具体类型防类型擦除")
    void shouldDeepCloneCollectionsAndImmutableTypesWithoutTypeErasure() {
        // 1. 不可变 List 与元素类型保持
        List<UserInfo> originalList = List.of(
                UserInfo.builder().userId(1L).username("User-1").build(),
                UserInfo.builder().userId(2L).username("User-2").build()
        );
        List<UserInfo> clonedList = JsonUtil.deepClone(originalList);
        assertNotNull(clonedList);
        assertEquals(2, clonedList.size());
        assertInstanceOf(UserInfo.class, clonedList.get(0));
        assertEquals("User-1", clonedList.get(0).getUsername());
        assertNotSame(originalList.get(0), clonedList.get(0));

        // 2. Set 集合克隆
        Set<String> originalSet = Set.of("apple", "banana");
        Set<String> clonedSet = JsonUtil.deepClone(originalSet);
        assertNotNull(clonedSet);
        assertEquals(2, clonedSet.size());
        assertTrue(clonedSet.contains("apple"));

        // 3. Map 集合克隆
        Map<String, UserInfo> originalMap = Map.of(
                "admin", UserInfo.builder().userId(99L).username("Admin").build()
        );
        Map<String, UserInfo> clonedMap = JsonUtil.deepClone(originalMap);
        assertNotNull(clonedMap);
        assertInstanceOf(UserInfo.class, clonedMap.get("admin"));
        assertEquals("Admin", clonedMap.get("admin").getUsername());
        assertNotSame(originalMap.get("admin"), clonedMap.get("admin"));
    }

    @Test
    @DisplayName("对抗性用例：自引用循环对象克隆失败时应该转译抛出受管 IllegalArgumentException")
    void shouldPropagateIllegalArgumentExceptionOnCycleOrSerializationFailure() {
        CyclicNode node = new CyclicNode();
        node.setName("node-1");
        node.setNext(node); // 自引用循环导致序列化异常

        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class, () -> JsonUtil.deepClone(node));
        assertTrue(ex.getMessage().startsWith("对象深度克隆失败"));
        assertNotNull(ex.getCause());
    }
}
