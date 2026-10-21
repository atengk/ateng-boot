package io.github.atengk.boot.core.spi;

import com.fasterxml.jackson.core.type.TypeReference;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Type;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 消息载荷序列化 SPI 单元测试
 *
 * @author Ateng
 * @since 2026-10-21
 */
@DisplayName("MessagePayloadSerializer 单元测试")
class MessagePayloadSerializerTest {

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    static class SampleOrder {
        private Long orderId;
        private String orderNo;
        private Double amount;
    }

    private final MessagePayloadSerializer serializer = MessagePayloadSerializer.defaultSerializer();

    @Test
    @DisplayName("POJO 对象序列化与 Class 反序列化测试")
    void testSerializeAndDeserializePojo() {
        SampleOrder order = new SampleOrder(1001L, "ORDER-20261021", 88.88);
        byte[] bytes = serializer.serialize(order);

        assertNotNull(bytes);
        assertTrue(bytes.length > 0);

        SampleOrder deserialized = serializer.deserialize(bytes, SampleOrder.class);
        assertNotNull(deserialized);
        assertEquals(1001L, deserialized.getOrderId());
        assertEquals("ORDER-20261021", deserialized.getOrderNo());
        assertEquals(88.88, deserialized.getAmount());
    }

    @Test
    @DisplayName("复杂泛型 List<POJO> 序列化与 Type 反序列化测试")
    void testSerializeAndDeserializeGenericList() {
        List<SampleOrder> list = List.of(
                new SampleOrder(1L, "ORD-1", 10.0),
                new SampleOrder(2L, "ORD-2", 20.0)
        );
        byte[] bytes = serializer.serialize(list);

        Type listType = new TypeReference<List<SampleOrder>>() {}.getType();
        List<SampleOrder> result = serializer.deserialize(bytes, listType);

        assertNotNull(result);
        assertEquals(2, result.size());
        assertEquals("ORD-1", result.get(0).getOrderNo());
        assertEquals(20.0, result.get(1).getAmount());
    }

    @Test
    @DisplayName("空值与边界防御测试")
    void testNullAndEmptyDefenses() {
        byte[] emptyBytes = serializer.serialize(null);
        assertEquals(0, emptyBytes.length);

        assertNull(serializer.deserialize(null, SampleOrder.class));
        assertNull(serializer.deserialize(new byte[0], SampleOrder.class));

        Type mapType = new TypeReference<Map<String, Object>>() {}.getType();
        assertNull(serializer.deserialize(null, mapType));
        assertNull(serializer.deserialize(new byte[0], mapType));
    }
}
