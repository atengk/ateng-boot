package io.github.atengk.boot.rabbitmq.converter;

import org.springframework.core.ParameterizedTypeReference;
import io.github.atengk.boot.core.util.JsonUtil;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageProperties;
import org.springframework.amqp.support.converter.DefaultJackson2JavaTypeMapper;
import org.springframework.amqp.support.converter.Jackson2JavaTypeMapper;
import org.springframework.amqp.support.converter.Jackson2JsonMessageConverter;

import java.lang.reflect.Type;
import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Jackson 消息转换器单元测试
 * 验证 INFERRED 推断模式、跨服务实体解耦、泛型集合反序列化与零 @class 污染
 *
 * @author Ateng
 * @since 2026-10-21
 */
@DisplayName("Jackson2JsonMessageConverter 单元测试")
class Jackson2JsonMessageConverterTest {

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    public static class ProducerOrderDTO {
        private Long orderId;
        private String orderNo;
        private Double amount;
    }

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    public static class ConsumerOrderVO {
        private Long orderId;
        private String orderNo;
        private Double amount;
    }

    private Jackson2JsonMessageConverter converter;

    @BeforeEach
    void setUp() {
        converter = new Jackson2JsonMessageConverter(JsonUtil.getObjectMapper());
        DefaultJackson2JavaTypeMapper typeMapper = new DefaultJackson2JavaTypeMapper();
        typeMapper.setTypePrecedence(Jackson2JavaTypeMapper.TypePrecedence.INFERRED);
        typeMapper.setTrustedPackages("*");
        converter.setJavaTypeMapper(typeMapper);
        converter.setAlwaysConvertToInferredType(true);
    }

    @Test
    @DisplayName("验证序列化生成纯净 JSON，零 @class 漏洞注入")
    void testSerializationZeroClassPollution() {
        ProducerOrderDTO dto = new ProducerOrderDTO(10086L, "ORD-2026", 99.9);
        Message message = converter.toMessage(dto, new MessageProperties());

        String json = new String(message.getBody(), StandardCharsets.UTF_8);
        assertFalse(json.contains("@class"), "序列化 JSON 严禁包含 @class 漏洞标记");
        assertTrue(json.contains("\"orderId\":10086"));
        assertTrue(json.contains("\"orderNo\":\"ORD-2026\""));
    }

    @Test
    @DisplayName("验证 INFERRED 推断模式：Header 中存在未知类名时仍能精准推断为目标 VO")
    void testInferredDeserializationIgnoresForeignTypeId() {
        ProducerOrderDTO dto = new ProducerOrderDTO(20088L, "ORD-FOREIGN", 188.8);
        Message message = converter.toMessage(dto, new MessageProperties());

        // 模拟外部微服务在 Header 中注入的未知全限定类名，且本服务消费端 @RabbitListener 推断为 ConsumerOrderVO
        message.getMessageProperties().setHeader("__TypeId__", "com.unknown.legacy.LegacyOrderEntity");
        message.getMessageProperties().setInferredArgumentType(ConsumerOrderVO.class);

        // INFERRED 模式优先以消费者目标类型反序列化，彻底解耦生产者内部类路径
        ConsumerOrderVO vo = (ConsumerOrderVO) converter.fromMessage(message);

        assertNotNull(vo);
        assertEquals(20088L, vo.getOrderId());
        assertEquals("ORD-FOREIGN", vo.getOrderNo());
        assertEquals(188.8, vo.getAmount());
    }

    @Test
    @DisplayName("验证泛型集合 List<DTO> 反序列化不退化为 LinkedHashMap")
    void testGenericListDeserialization() {
        List<ConsumerOrderVO> list = List.of(
                new ConsumerOrderVO(1L, "ORD-A", 10.0),
                new ConsumerOrderVO(2L, "ORD-B", 20.0)
        );
        Message message = converter.toMessage(list, new MessageProperties());

        // 1. 验证基于 InferredArgumentType 反序列化
        Type listType = new ParameterizedTypeReference<List<ConsumerOrderVO>>() {}.getType();
        message.getMessageProperties().setInferredArgumentType(listType);

        @SuppressWarnings("unchecked")
        List<ConsumerOrderVO> result = (List<ConsumerOrderVO>) converter.fromMessage(message);

        assertNotNull(result);
        assertEquals(2, result.size());
        assertInstanceOf(ConsumerOrderVO.class, result.get(0));
        assertEquals("ORD-A", result.get(0).getOrderNo());

        // 2. 验证基于 ParameterizedTypeReference ConversionHint 反序列化
        @SuppressWarnings("unchecked")
        List<ConsumerOrderVO> resultByHint = (List<ConsumerOrderVO>) converter.fromMessage(
                message, new ParameterizedTypeReference<List<ConsumerOrderVO>>() {});
        assertNotNull(resultByHint);
        assertEquals(2, resultByHint.size());
        assertInstanceOf(ConsumerOrderVO.class, resultByHint.get(0));
    }
}
