package io.github.atengk.boot.kafka.converter;

import io.github.atengk.boot.core.util.JsonUtil;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.common.header.internals.RecordHeaders;
import org.apache.kafka.common.serialization.ByteArrayDeserializer;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.kafka.support.converter.BatchMessagingMessageConverter;
import org.springframework.kafka.support.converter.ByteArrayJsonMessageConverter;
import org.springframework.kafka.support.serializer.ErrorHandlingDeserializer;
import org.springframework.messaging.Message;

import java.lang.reflect.Method;
import java.lang.reflect.Type;
import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 消息转换与反序列化推断测试
 *
 * @author Ateng
 * @since 2026-10-22
 */
@DisplayName("Kafka 消息转换与反序列化推断测试")
class KafkaMessageConverterTest {

    public static class OrderItem {
        private String id;
        private String name;

        public OrderItem() {}

        public OrderItem(String id, String name) {
            this.id = id;
            this.name = name;
        }

        public String getId() { return id; }
        public void setId(String id) { this.id = id; }
        public String getName() { return name; }
        public void setName(String name) { this.name = name; }
    }

    public void listenSingle(OrderItem item) {}
    public void listenBatch(List<OrderItem> items) {}

    @Test
    @DisplayName("推断类型单条反序列化，解耦微服务类全限定名")
    void testInferredSingleDeserialization() throws NoSuchMethodException {
        ByteArrayJsonMessageConverter converter = new ByteArrayJsonMessageConverter(JsonUtil.getObjectMapper());

        byte[] payload = "{\"id\":\"A100\",\"name\":\"Book\"}".getBytes(StandardCharsets.UTF_8);
        ConsumerRecord<String, byte[]> record = new ConsumerRecord<>("order.topic", 0, 1L, "k1", payload);

        Method method = getClass().getMethod("listenSingle", OrderItem.class);
        Type targetType = method.getGenericParameterTypes()[0];

        Message<?> message = converter.toMessage(record, null, null, targetType);
        assertThat(message.getPayload()).isInstanceOf(OrderItem.class);
        OrderItem item = (OrderItem) message.getPayload();
        assertThat(item.getId()).isEqualTo("A100");
        assertThat(item.getName()).isEqualTo("Book");
    }

    @Test
    @DisplayName("泛型集合精准推断反序列化，坚决杜绝 List<LinkedHashMap> 运行时强转异常")
    void testParameterizedTypeDeserialization() throws NoSuchMethodException {
        ByteArrayJsonMessageConverter converter = new ByteArrayJsonMessageConverter(JsonUtil.getObjectMapper());

        byte[] payload = "[{\"id\":\"A100\",\"name\":\"Book\"},{\"id\":\"A200\",\"name\":\"Pen\"}]".getBytes(StandardCharsets.UTF_8);
        ConsumerRecord<String, byte[]> record = new ConsumerRecord<>("order.topic", 0, 2L, "k2", payload);

        Method method = getClass().getMethod("listenBatch", List.class);
        Type targetType = method.getGenericParameterTypes()[0];

        Message<?> message = converter.toMessage(record, null, null, targetType);
        assertThat(message.getPayload()).isInstanceOf(List.class);

        @SuppressWarnings("unchecked")
        List<OrderItem> list = (List<OrderItem>) message.getPayload();
        assertThat(list).hasSize(2);
        assertThat(list.get(0)).isInstanceOf(OrderItem.class);
        assertThat(list.get(0).getName()).isEqualTo("Book");
        assertThat(list.get(1).getName()).isEqualTo("Pen");
    }

    @Test
    @DisplayName("ErrorHandlingDeserializer 包装 ByteArrayDeserializer 正常放行原始字节流")
    void testErrorHandlingDeserializerPassThrough() {
        ErrorHandlingDeserializer<byte[]> errorHandlingDeserializer = new ErrorHandlingDeserializer<>(new ByteArrayDeserializer());
        byte[] raw = "sample-bytes".getBytes(StandardCharsets.UTF_8);
        byte[] result = errorHandlingDeserializer.deserialize("topic", new RecordHeaders(), raw);
        assertThat(result).isEqualTo(raw);
    }
}
