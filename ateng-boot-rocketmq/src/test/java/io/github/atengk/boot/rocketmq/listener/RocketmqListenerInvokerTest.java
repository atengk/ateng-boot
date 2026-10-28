package io.github.atengk.boot.rocketmq.listener;

import io.github.atengk.boot.core.constant.MessageHeaderConstants;
import io.github.atengk.boot.core.context.MessageContextPropagator;
import io.github.atengk.boot.core.context.TenantContextHolder;
import io.github.atengk.boot.core.context.UserContextHolder;
import io.github.atengk.boot.core.exception.IdempotentException;
import io.github.atengk.boot.core.util.JsonUtil;
import io.github.atengk.boot.rocketmq.constant.RocketmqConstants;
import io.github.atengk.boot.rocketmq.helper.RocketmqMessageHelper;
import jakarta.validation.ConstraintViolation;
import jakarta.validation.Valid;
import jakarta.validation.Validator;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import org.apache.rocketmq.client.apis.consumer.ConsumeResult;
import org.apache.rocketmq.client.apis.message.MessageId;
import org.apache.rocketmq.client.apis.message.MessageView;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;

import java.io.IOException;
import java.lang.reflect.Method;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

/**
 * RocketMQ 消费调用执行器单元测试
 *
 * @author Ateng
 * @since 2026-10-22
 */
@DisplayName("RocketmqListenerInvoker 消费调用执行器单元测试")
class RocketmqListenerInvokerTest {

    private Validator validator;

    @BeforeEach
    void setUp() {
        validator = mock(Validator.class);
    }

    @AfterEach
    void tearDown() {
        MDC.clear();
        TenantContextHolder.clear();
        UserContextHolder.clear();
    }

    private MessageView createMockMessageView(byte[] bodyBytes, Map<String, String> properties, String tag, String key) {
        MessageView mv = mock(MessageView.class);
        ByteBuffer buffer = ByteBuffer.wrap(bodyBytes != null ? bodyBytes : new byte[0]);
        when(mv.getBody()).thenReturn(buffer);
        when(mv.getProperties()).thenReturn(properties != null ? properties : Collections.emptyMap());
        when(mv.getTag()).thenReturn(Optional.ofNullable(tag));
        when(mv.getKeys()).thenReturn(key != null ? Collections.singleton(key) : Collections.emptyList());

        MessageId messageId = mock(MessageId.class);
        when(messageId.toString()).thenReturn("MSG-TEST-001");
        when(mv.getMessageId()).thenReturn(messageId);
        return mv;
    }

    // =========================================================================
    // 1. 推断反序列化测试 (Inferred Deserialization)
    // =========================================================================

    @Test
    @DisplayName("推断反序列化：单 DTO 对象入参成功接收并消费")
    void testConsumeSingleDto() throws Exception {
        OrderService service = new OrderService();
        Method method = OrderService.class.getDeclaredMethod("handleOrder", OrderDTO.class);
        RocketmqListenerInvoker invoker = new RocketmqListenerInvoker(service, method, "order_topic", "order_group");

        OrderDTO order = new OrderDTO("ORD-100", 99.9);
        byte[] bytes = JsonUtil.toJsonBytes(order);
        MessageView mv = createMockMessageView(bytes, null, "create", "KEY-100");

        ConsumeResult result = invoker.consume(mv);

        assertThat(result).isEqualTo(ConsumeResult.SUCCESS);
        assertThat(service.receivedOrder.get()).isNotNull();
        assertThat(service.receivedOrder.get().getOrderNo()).isEqualTo("ORD-100");
        assertThat(service.receivedOrder.get().getAmount()).isEqualTo(99.9);
    }

    @Test
    @DisplayName("推断反序列化：泛型集合 List<OrderItemDTO> 深度解析，杜绝 LinkedHashMap 强转异常")
    void testConsumeGenericList() throws Exception {
        OrderService service = new OrderService();
        Method method = OrderService.class.getDeclaredMethod("handleOrderItems", List.class);
        RocketmqListenerInvoker invoker = new RocketmqListenerInvoker(service, method, "order_topic", "order_group");

        List<OrderItemDTO> items = List.of(new OrderItemDTO("ITEM-1", 2), new OrderItemDTO("ITEM-2", 5));
        byte[] bytes = JsonUtil.toJsonBytes(items);
        MessageView mv = createMockMessageView(bytes, null, "items", "KEY-ITEMS");

        ConsumeResult result = invoker.consume(mv);

        assertThat(result).isEqualTo(ConsumeResult.SUCCESS);
        assertThat(service.receivedItems.get()).hasSize(2);
        assertThat(service.receivedItems.get().get(0)).isInstanceOf(OrderItemDTO.class);
        assertThat(service.receivedItems.get().get(0).getItemId()).isEqualTo("ITEM-1");
    }

    @Test
    @DisplayName("推断反序列化：String 与 byte[] 原生报文入参正确解析")
    void testConsumeStringAndBytes() throws Exception {
        RawMessageService service = new RawMessageService();
        Method stringMethod = RawMessageService.class.getDeclaredMethod("handleString", String.class);
        RocketmqListenerInvoker invokerStr = new RocketmqListenerInvoker(service, stringMethod, "raw_topic", "raw_group");

        MessageView mvStr = createMockMessageView("Hello RocketMQ".getBytes(StandardCharsets.UTF_8), null, null, null);
        assertThat(invokerStr.consume(mvStr)).isEqualTo(ConsumeResult.SUCCESS);
        assertThat(service.receivedString.get()).isEqualTo("Hello RocketMQ");

        Method byteMethod = RawMessageService.class.getDeclaredMethod("handleBytes", byte[].class);
        RocketmqListenerInvoker invokerByte = new RocketmqListenerInvoker(service, byteMethod, "raw_topic", "raw_group");

        byte[] rawBytes = new byte[]{1, 2, 3, 4};
        MessageView mvByte = createMockMessageView(rawBytes, null, null, null);
        assertThat(invokerByte.consume(mvByte)).isEqualTo(ConsumeResult.SUCCESS);
        assertThat(service.receivedBytes.get()).isEqualTo(rawBytes);
    }

    @Test
    @DisplayName("推断反序列化：MessageView 原生快照入参直接传递")
    void testConsumeMessageViewDirectly() throws Exception {
        RawMessageService service = new RawMessageService();
        Method method = RawMessageService.class.getDeclaredMethod("handleMessageView", MessageView.class);
        RocketmqListenerInvoker invoker = new RocketmqListenerInvoker(service, method, "raw_topic", "raw_group");

        MessageView mv = createMockMessageView("test".getBytes(StandardCharsets.UTF_8), null, null, null);
        assertThat(invoker.consume(mv)).isEqualTo(ConsumeResult.SUCCESS);
        assertThat(service.receivedView.get()).isSameAs(mv);
    }

    // =========================================================================
    // 2. 透明 GZIP 解压缩测试
    // =========================================================================

    @Test
    @DisplayName("透明解压：Header 携带 X-Compression: gzip 时自动解压再反序列化")
    void testDecompressGzip() throws Exception {
        OrderService service = new OrderService();
        Method method = OrderService.class.getDeclaredMethod("handleOrder", OrderDTO.class);
        RocketmqListenerInvoker invoker = new RocketmqListenerInvoker(service, method, "order_topic", "order_group");

        OrderDTO order = new OrderDTO("ORD-GZIP-1", 888.8);
        byte[] uncompressed = JsonUtil.toJsonBytes(order);
        byte[] compressed = RocketmqMessageHelper.compressGzip(uncompressed);

        Map<String, String> properties = new HashMap<>();
        properties.put(RocketmqConstants.HEADER_COMPRESSION, RocketmqConstants.COMPRESSION_GZIP);

        MessageView mv = createMockMessageView(compressed, properties, "gzip", "KEY-GZIP");
        ConsumeResult result = invoker.consume(mv);

        assertThat(result).isEqualTo(ConsumeResult.SUCCESS);
        assertThat(service.receivedOrder.get().getOrderNo()).isEqualTo("ORD-GZIP-1");
    }

    // =========================================================================
    // 3. 毒丸异常防卫测试 (Poison Pill Defense)
    // =========================================================================

    @Test
    @DisplayName("毒丸防卫：畸形 JSON 报文反序列化失败安全丢弃，返回 SUCCESS 阻断自旋重试")
    void testPoisonPillMalformedJson() throws Exception {
        OrderService service = new OrderService();
        Method method = OrderService.class.getDeclaredMethod("handleOrder", OrderDTO.class);
        RocketmqListenerInvoker invoker = new RocketmqListenerInvoker(service, method, "order_topic", "order_group");

        byte[] malformedBytes = "{ malformed json :::".getBytes(StandardCharsets.UTF_8);
        MessageView mv = createMockMessageView(malformedBytes, null, "bad", "KEY-BAD");

        ConsumeResult result = invoker.consume(mv);

        assertThat(result).isEqualTo(ConsumeResult.SUCCESS);
        assertThat(service.receivedOrder.get()).isNull();
    }

    @Test
    @DisplayName("毒丸防卫：GZIP 解压缩失败报文安全丢弃，返回 SUCCESS 阻断重试")
    void testPoisonPillCorruptedGzip() throws Exception {
        OrderService service = new OrderService();
        Method method = OrderService.class.getDeclaredMethod("handleOrder", OrderDTO.class);
        RocketmqListenerInvoker invoker = new RocketmqListenerInvoker(service, method, "order_topic", "order_group");

        Map<String, String> properties = new HashMap<>();
        properties.put(RocketmqConstants.HEADER_COMPRESSION, RocketmqConstants.COMPRESSION_GZIP);

        byte[] corruptedGzip = "not a valid gzip header".getBytes(StandardCharsets.UTF_8);
        MessageView mv = createMockMessageView(corruptedGzip, properties, "bad_gzip", "KEY-CORRUPTED");

        ConsumeResult result = invoker.consume(mv);
        assertThat(result).isEqualTo(ConsumeResult.SUCCESS);
    }

    @Test
    @DisplayName("毒丸防卫：JSR-303 参数校验失败安全丢弃，返回 SUCCESS 阻断重试")
    void testPoisonPillValidationFailure() throws Exception {
        OrderService service = new OrderService();
        Method method = OrderService.class.getDeclaredMethod("handleValidatedOrder", ValidatedOrderDTO.class);

        ConstraintViolation<Object> violation = mock(ConstraintViolation.class);
        when(validator.validate(any())).thenReturn(Collections.singleton(violation));

        RocketmqListenerInvoker invoker = new RocketmqListenerInvoker(service, method, "order_topic", "order_group", validator);

        // 模拟触发校验失败
        ValidatedOrderDTO invalidOrder = new ValidatedOrderDTO(null, null);
        byte[] bytes = JsonUtil.toJsonBytes(invalidOrder);
        MessageView mv = createMockMessageView(bytes, null, "valid_test", "KEY-INVALID");

        ConsumeResult result = invoker.consume(mv);

        assertThat(result).isEqualTo(ConsumeResult.SUCCESS);
        assertThat(service.receivedValidatedOrder.get()).isNull();
    }

    // =========================================================================
    // 4. 防重幂等静默确认测试 (Idempotent Silent ACK)
    // =========================================================================

    @Test
    @DisplayName("幂等防重：业务触发 IdempotentException 时静默确认，返回 SUCCESS 阻断自旋")
    void testIdempotentDuplicateSilentAck() throws Exception {
        OrderService service = new OrderService();
        Method method = OrderService.class.getDeclaredMethod("handleOrderWithIdempotent", OrderDTO.class);
        RocketmqListenerInvoker invoker = new RocketmqListenerInvoker(service, method, "order_topic", "order_group");

        OrderDTO order = new OrderDTO("ORD-IDEMP", 100.0);
        byte[] bytes = JsonUtil.toJsonBytes(order);
        MessageView mv = createMockMessageView(bytes, null, "idemp", "KEY-IDEMP");

        ConsumeResult result = invoker.consume(mv);

        assertThat(result).isEqualTo(ConsumeResult.SUCCESS);
    }

    // =========================================================================
    // 5. 瞬态业务异常重试测试
    // =========================================================================

    @Test
    @DisplayName("瞬态异常：普通业务异常返回 FAILURE，触发 RocketMQ 服务端重试")
    void testTransientBusinessFailureReturnsFailure() throws Exception {
        OrderService service = new OrderService();
        Method method = OrderService.class.getDeclaredMethod("handleOrderWithTransientError", OrderDTO.class);
        RocketmqListenerInvoker invoker = new RocketmqListenerInvoker(service, method, "order_topic", "order_group");

        OrderDTO order = new OrderDTO("ORD-FAIL", 50.0);
        byte[] bytes = JsonUtil.toJsonBytes(order);
        MessageView mv = createMockMessageView(bytes, null, "transient", "KEY-FAIL");

        ConsumeResult result = invoker.consume(mv);

        assertThat(result).isEqualTo(ConsumeResult.FAILURE);
    }

    // =========================================================================
    // 6. 四维上下文激活与确定性清理测试
    // =========================================================================

    @Test
    @DisplayName("上下文生命周期：消费执行时激活 MDC traceId、租户、用户，完成后严格清理零污染")
    void testContextLifecycleAndDeterministicCleanup() throws Exception {
        OrderService service = new OrderService();
        Method method = OrderService.class.getDeclaredMethod("handleOrderWithContextCheck", OrderDTO.class);
        RocketmqListenerInvoker invoker = new RocketmqListenerInvoker(service, method, "order_topic", "order_group");

        Map<String, String> properties = new HashMap<>();
        properties.put(MessageHeaderConstants.TRACE_ID, "trace-consume-999");
        properties.put(MessageHeaderConstants.TENANT_ID, "tenant-consume-888");
        properties.put(MessageHeaderConstants.USER_ID, "user-consume-777");

        OrderDTO order = new OrderDTO("ORD-CTX", 1.0);
        byte[] bytes = JsonUtil.toJsonBytes(order);
        MessageView mv = createMockMessageView(bytes, properties, "ctx", "KEY-CTX");

        ConsumeResult result = invoker.consume(mv);

        assertThat(result).isEqualTo(ConsumeResult.SUCCESS);
        assertThat(service.contextVerified.get()).isTrue();

        // 验证执行完毕后线程上下文彻底还原，零泄漏
        assertThat(MDC.get(MessageContextPropagator.MDC_TRACE_ID_KEY)).isNull();
        assertThat(TenantContextHolder.getTenantId()).isNull();
        assertThat(UserContextHolder.get()).isNull();
    }

    // =========================================================================
    // 测试辅助 Bean 与 DTO
    // =========================================================================

    public static class OrderDTO {
        private String orderNo;
        private Double amount;

        public OrderDTO() {}

        public OrderDTO(String orderNo, Double amount) {
            this.orderNo = orderNo;
            this.amount = amount;
        }

        public String getOrderNo() { return orderNo; }
        public void setOrderNo(String orderNo) { this.orderNo = orderNo; }
        public Double getAmount() { return amount; }
        public void setAmount(Double amount) { this.amount = amount; }
    }

    public static class OrderItemDTO {
        private String itemId;
        private Integer quantity;

        public OrderItemDTO() {}

        public OrderItemDTO(String itemId, Integer quantity) {
            this.itemId = itemId;
            this.quantity = quantity;
        }

        public String getItemId() { return itemId; }
        public void setItemId(String itemId) { this.itemId = itemId; }
        public Integer getQuantity() { return quantity; }
        public void setQuantity(Integer quantity) { this.quantity = quantity; }
    }

    public static class ValidatedOrderDTO {
        @NotBlank(message = "orderNo 不能为空")
        private String orderNo;
        @NotNull(message = "amount 不能为空")
        private Double amount;

        public ValidatedOrderDTO() {}

        public ValidatedOrderDTO(String orderNo, Double amount) {
            this.orderNo = orderNo;
            this.amount = amount;
        }

        public String getOrderNo() { return orderNo; }
        public void setOrderNo(String orderNo) { this.orderNo = orderNo; }
        public Double getAmount() { return amount; }
        public void setAmount(Double amount) { this.amount = amount; }
    }

    public static class OrderService {
        final AtomicReference<OrderDTO> receivedOrder = new AtomicReference<>();
        final AtomicReference<List<OrderItemDTO>> receivedItems = new AtomicReference<>();
        final AtomicReference<ValidatedOrderDTO> receivedValidatedOrder = new AtomicReference<>();
        final AtomicBoolean contextVerified = new AtomicBoolean(false);

        public void handleOrder(OrderDTO order) {
            receivedOrder.set(order);
        }

        public void handleOrderItems(List<OrderItemDTO> items) {
            receivedItems.set(items);
        }

        public void handleValidatedOrder(@Valid ValidatedOrderDTO order) {
            receivedValidatedOrder.set(order);
        }

        public void handleOrderWithIdempotent(OrderDTO order) {
            throw new IdempotentException("订单 ORD-IDEMP 正在并发处理中，触发防重拦截");
        }

        public void handleOrderWithTransientError(OrderDTO order) {
            throw new RuntimeException("数据库网络闪断，需要重试");
        }

        public void handleOrderWithContextCheck(OrderDTO order) {
            assertThat(MDC.get(MessageContextPropagator.MDC_TRACE_ID_KEY)).isEqualTo("trace-consume-999");
            assertThat(TenantContextHolder.getTenantId()).isEqualTo("tenant-consume-888");
            assertThat(UserContextHolder.get()).isEqualTo("user-consume-777");
            contextVerified.set(true);
        }
    }

    public static class RawMessageService {
        final AtomicReference<String> receivedString = new AtomicReference<>();
        final AtomicReference<byte[]> receivedBytes = new AtomicReference<>();
        final AtomicReference<MessageView> receivedView = new AtomicReference<>();

        public void handleString(String str) {
            receivedString.set(str);
        }

        public void handleBytes(byte[] bytes) {
            receivedBytes.set(bytes);
        }

        public void handleMessageView(MessageView view) {
            receivedView.set(view);
        }
    }
}
