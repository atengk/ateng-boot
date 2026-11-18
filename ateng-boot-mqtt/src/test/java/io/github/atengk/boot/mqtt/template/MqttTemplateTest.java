package io.github.atengk.boot.mqtt.template;

import io.github.atengk.boot.core.context.TenantContextHolder;
import io.github.atengk.boot.core.context.UserContextHolder;
import io.github.atengk.boot.core.exception.BusinessException;
import io.github.atengk.boot.mqtt.context.MqttContextHolder;
import io.github.atengk.boot.mqtt.defense.PayloadGuard;
import io.github.atengk.boot.mqtt.handler.MqttSendFailureHandler;
import io.github.atengk.boot.mqtt.model.MqttPublishRecord;
import io.github.atengk.boot.mqtt.properties.AtengMqttProperties;
import io.github.atengk.boot.mqtt.properties.ClientIdStrategy;
import io.github.atengk.boot.mqtt.properties.MqttClientProperties;
import io.github.atengk.boot.mqtt.properties.OfflineBufferPolicy;
import io.github.atengk.boot.mqtt.properties.PersistenceType;
import io.github.atengk.boot.mqtt.registry.MqttClientHandle;
import io.github.atengk.boot.mqtt.registry.NamedMqttClientRegistry;
import io.github.atengk.boot.mqtt.requestreply.MqttRequestReplyManager;
import io.github.atengk.boot.mqtt.test.EmbeddedMqttBroker;
import io.netty.handler.codec.mqtt.MqttProperties;
import io.netty.handler.codec.mqtt.MqttQoS;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.slf4j.MDC;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.util.unit.DataSize;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * MqttTemplate 核心发布门面、双轨四维上下文透传与本地事务安全测试
 *
 * @author Ateng
 * @since 2026-10-23
 */
class MqttTemplateTest {

    private EmbeddedMqttBroker broker;
    private NamedMqttClientRegistry clientRegistry;
    private PayloadGuard payloadGuard;
    private MqttTemplate mqttTemplate;
    private final List<FailureRecord> failureRecords = new CopyOnWriteArrayList<>();

    private record FailureRecord(String clientName, String topic, byte[] payload, int qos, boolean retained, Throwable cause) {}

    public record TestTelemetryPayload(String deviceId, double temperature, long timestamp) {}

    @BeforeEach
    void setUp(@TempDir Path tempDir) {
        // 1. 启动轻量纯内存 MQTT Broker
        broker = new EmbeddedMqttBroker(0);
        broker.start();

        // 2. 配置多命名客户端
        AtengMqttProperties properties = new AtengMqttProperties();
        properties.setDefaultClient("primary");

        Map<String, MqttClientProperties> clients = new HashMap<>();

        // primary 客户端 (MQTT 5.0)
        MqttClientProperties primary = new MqttClientProperties();
        primary.setServerUri(broker.getServerUri());
        primary.setClientId("test-primary");
        primary.setClientIdStrategy(ClientIdStrategy.STATIC);
        primary.setCleanStart(true);
        primary.setConnectionTimeout(Duration.ofSeconds(3));
        primary.setPublishTimeout(Duration.ofSeconds(3));
        primary.setMaxPayloadSize(DataSize.ofMegabytes(1));
        primary.setPersistenceType(PersistenceType.MEMORY);
        primary.setProtocolVersion("5.0");
        clients.put("primary", primary);

        // secondary 客户端 (MQTT 5.0，大报文防卫限 1KB)
        MqttClientProperties secondary = new MqttClientProperties();
        secondary.setServerUri(broker.getServerUri());
        secondary.setClientId("test-secondary");
        secondary.setClientIdStrategy(ClientIdStrategy.STATIC);
        secondary.setCleanStart(true);
        secondary.setConnectionTimeout(Duration.ofSeconds(3));
        secondary.setPublishTimeout(Duration.ofSeconds(3));
        secondary.setMaxPayloadSize(DataSize.ofKilobytes(1)); // 限 1KB
        secondary.setPersistenceType(PersistenceType.MEMORY);
        secondary.setProtocolVersion("5.0");
        clients.put("secondary", secondary);

        // v3client 客户端 (MQTT 3.1.1 协议)
        MqttClientProperties v3client = new MqttClientProperties();
        v3client.setServerUri(broker.getServerUri());
        v3client.setClientId("test-v3");
        v3client.setClientIdStrategy(ClientIdStrategy.STATIC);
        v3client.setCleanStart(true);
        v3client.setConnectionTimeout(Duration.ofSeconds(3));
        v3client.setPublishTimeout(Duration.ofSeconds(3));
        v3client.setMaxPayloadSize(DataSize.ofMegabytes(1));
        v3client.setPersistenceType(PersistenceType.MEMORY);
        v3client.setProtocolVersion("3.1.1");
        clients.put("v3client", v3client);

        properties.setClients(clients);

        // 3. 初始化 Registry
        clientRegistry = new NamedMqttClientRegistry(properties);
        clientRegistry.initClients();

        // 4. 构建 MqttTemplate
        payloadGuard = new PayloadGuard();
        MqttSendFailureHandler failureHandler = (clientName, topic, payload, qos, retained, cause) ->
                failureRecords.add(new FailureRecord(clientName, topic, payload, qos, retained, cause));

        mqttTemplate = new MqttTemplate(clientRegistry, payloadGuard, failureHandler);
    }

    @AfterEach
    void tearDown() {
        // 清理线程上下文环境，恪守零污染铁律
        MDC.clear();
        TenantContextHolder.clear();
        UserContextHolder.clear();
        MqttContextHolder.clear();

        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.clearSynchronization();
        }

        if (clientRegistry != null) {
            clientRegistry.destroy();
        }
        if (broker != null) {
            broker.stop();
        }
        failureRecords.clear();
    }

    @Test
    @DisplayName("验证 QoS 0、1、2 消息发布能够准确投递至 Broker")
    void shouldPublishDifferentQosMessages() throws Exception {
        mqttTemplate.publish("test/qos0", "hello-qos-0", 0);
        mqttTemplate.publish("test/qos1", "hello-qos-1", 1);
        mqttTemplate.publish("test/qos2", "hello-qos-2", 2);

        Thread.sleep(200);

        List<EmbeddedMqttBroker.ReceivedMessage> msgs = broker.getReceivedMessages();
        assertThat(msgs).hasSize(3);

        assertThat(msgs.get(0).getTopic()).isEqualTo("test/qos0");
        assertThat(msgs.get(0).getQos()).isEqualTo(MqttQoS.AT_MOST_ONCE);
        assertThat(new String(msgs.get(0).getPayload(), StandardCharsets.UTF_8)).isEqualTo("hello-qos-0");
        assertThat(msgs.get(0).isRetained()).isFalse();

        assertThat(msgs.get(1).getTopic()).isEqualTo("test/qos1");
        assertThat(msgs.get(1).getQos()).isEqualTo(MqttQoS.AT_LEAST_ONCE);
        assertThat(new String(msgs.get(1).getPayload(), StandardCharsets.UTF_8)).isEqualTo("hello-qos-1");

        assertThat(msgs.get(2).getTopic()).isEqualTo("test/qos2");
        assertThat(msgs.get(2).getQos()).isEqualTo(MqttQoS.EXACTLY_ONCE);
        assertThat(new String(msgs.get(2).getPayload(), StandardCharsets.UTF_8)).isEqualTo("hello-qos-2");
    }

    @Test
    @DisplayName("验证 Retained 保留消息正确标记发布")
    void shouldPublishRetainedMessage() throws Exception {
        mqttTemplate.publishRetained("test/retained", "retained-content", 1);

        Thread.sleep(150);

        List<EmbeddedMqttBroker.ReceivedMessage> msgs = broker.getReceivedMessages();
        assertThat(msgs).hasSize(1);
        assertThat(msgs.get(0).getTopic()).isEqualTo("test/retained");
        assertThat(msgs.get(0).isRetained()).isTrue();
        assertThat(new String(msgs.get(0).getPayload(), StandardCharsets.UTF_8)).isEqualTo("retained-content");
    }

    @Test
    @DisplayName("验证载荷自适应转译：支持 byte[]、String 与 POJO 对象序列化")
    void shouldSupportMultiplePayloadTypes() throws Exception {
        // 1. byte[]
        byte[] rawBytes = new byte[]{0x01, 0x02, 0x03, 0x04};
        mqttTemplate.publish("test/bytes", rawBytes);

        // 2. String
        mqttTemplate.publish("test/string", "plain-text");

        // 3. POJO Object
        TestTelemetryPayload pojo = new TestTelemetryPayload("DEV-001", 26.5, 1700000000000L);
        mqttTemplate.publish("test/pojo", pojo);

        Thread.sleep(200);

        List<EmbeddedMqttBroker.ReceivedMessage> msgs = broker.getReceivedMessages();
        assertThat(msgs).hasSize(3);

        assertThat(msgs.get(0).getPayload()).isEqualTo(rawBytes);
        assertThat(new String(msgs.get(1).getPayload(), StandardCharsets.UTF_8)).isEqualTo("plain-text");

        String pojoJson = new String(msgs.get(2).getPayload(), StandardCharsets.UTF_8);
        assertThat(pojoJson).contains("DEV-001").contains("26.5");
    }

    @Test
    @DisplayName("验证 MQTT 5.0 出站报文正确携带 User Properties 四维上下文")
    void shouldInjectFourDimensionalContextInMqtt5() throws Exception {
        // 设置四维上下文
        MDC.put("traceId", "trace-xyz-777");
        TenantContextHolder.setTenantId("tenant-999");
        UserContextHolder.set("user-admin-1");
        TenantContextHolder.setIgnoreTenant(true);

        mqttTemplate.publish("test/context/v5", "payload-with-context");

        Thread.sleep(150);

        List<EmbeddedMqttBroker.ReceivedMessage> msgs = broker.getReceivedMessages();
        assertThat(msgs).hasSize(1);
        EmbeddedMqttBroker.ReceivedMessage received = msgs.get(0);

        MqttProperties properties = received.getProperties();
        assertThat(properties).isNotNull();

        // 提取 User Properties
        Map<String, String> userPropsMap = new HashMap<>();
        for (MqttProperties.MqttProperty<?> prop : properties.listAll()) {
            if (prop instanceof MqttProperties.UserProperties up) {
                for (MqttProperties.StringPair pair : up.value()) {
                    userPropsMap.put(pair.key, pair.value);
                }
            } else if (prop instanceof MqttProperties.UserProperty up) {
                userPropsMap.put(up.value().key, up.value().value);
            }
        }

        assertThat(userPropsMap.get("X-Trace-Id")).isEqualTo("trace-xyz-777");
        assertThat(userPropsMap.get("X-Tenant-Id")).isEqualTo("tenant-999");
        assertThat(userPropsMap.get("X-User-Id")).isEqualTo("user-admin-1");
        assertThat(userPropsMap.get("X-Ignore-Tenant")).isEqualTo("true");
        assertThat(userPropsMap.get("X-Send-Timestamp")).isNotNull();
    }

    @Test
    @DisplayName("验证 MQTT 3.1.1 连接保持载荷 100% 原始纯真，零侵入包装")
    void shouldKeepPayloadPureInMqtt3() throws Exception {
        // 绑定上下文
        MDC.put("traceId", "trace-v3-pure");
        TenantContextHolder.setTenantId("tenant-v3");

        String rawContent = "RAW_HEX_OR_CSV_DATA:123,456";
        mqttTemplate.publishToClient("v3client", "test/v3/pure", rawContent);

        Thread.sleep(150);

        List<EmbeddedMqttBroker.ReceivedMessage> msgs = broker.getReceivedMessages();
        assertThat(msgs).hasSize(1);
        EmbeddedMqttBroker.ReceivedMessage received = msgs.get(0);

        // 载荷 100% 保持原始纯真
        assertThat(new String(received.getPayload(), StandardCharsets.UTF_8)).isEqualTo(rawContent);
    }

    @Test
    @DisplayName("验证 PayloadGuard 对超出 maxPayloadSize 的大报文实施快速失败拦截")
    void shouldRejectOverSizedPayload() {
        // secondary 客户端设置了 maxPayloadSize = 1KB (1024 bytes)
        byte[] largePayload = new byte[2048]; // 2KB

        assertThatThrownBy(() -> mqttTemplate.publishToClient("secondary", "test/oversized", largePayload))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("exceeds configured maxPayloadSize limit");

        // 验证 Broker 未收到该非法大报文
        assertThat(broker.getReceivedMessages()).isEmpty();
    }

    @Test
    @DisplayName("验证 sendAfterCommit 在非事务上下文时平滑降级就地直接发信")
    void shouldSendImmediatelyWhenNoTransactionActive() throws Exception {
        mqttTemplate.sendAfterCommit("test/tx/direct", "direct-msg");

        Thread.sleep(150);

        List<EmbeddedMqttBroker.ReceivedMessage> msgs = broker.getReceivedMessages();
        assertThat(msgs).hasSize(1);
        assertThat(msgs.get(0).getTopic()).isEqualTo("test/tx/direct");
        assertThat(new String(msgs.get(0).getPayload(), StandardCharsets.UTF_8)).isEqualTo("direct-msg");
    }

    @Test
    @DisplayName("验证 sendAfterCommit 在事务激活时延迟至提交后投递，事务回滚时安全取消")
    void shouldDelaySendUntilCommitAndCancelOnRollback() throws Exception {
        // 1. 模拟激活 Spring 本地物理事务同步
        TransactionSynchronizationManager.initSynchronization();

        try {
            // 2. 调用 sendAfterCommit
            mqttTemplate.sendAfterCommit("test/tx/committed", "delayed-msg");

            // 在事务提交前，Broker 绝不应收到消息
            Thread.sleep(100);
            assertThat(broker.getReceivedMessages()).isEmpty();

            // 3. 模拟物理事务提交 (afterCommit)
            List<TransactionSynchronization> synchronizations = TransactionSynchronizationManager.getSynchronizations();
            for (TransactionSynchronization sync : synchronizations) {
                sync.afterCommit();
            }

            Thread.sleep(150);
            assertThat(broker.getReceivedMessages()).hasSize(1);
            assertThat(broker.getReceivedMessages().get(0).getTopic()).isEqualTo("test/tx/committed");
        } finally {
            TransactionSynchronizationManager.clearSynchronization();
        }

        // 4. 验证事务回滚场景
        broker.clearReceivedMessages();
        TransactionSynchronizationManager.initSynchronization();
        try {
            mqttTemplate.sendAfterCommit("test/tx/rollback", "rollback-msg");

            // 模拟事务回滚 (afterCompletion STATUS_ROLLED_BACK)
            List<TransactionSynchronization> synchronizations = TransactionSynchronizationManager.getSynchronizations();
            for (TransactionSynchronization sync : synchronizations) {
                sync.afterCompletion(TransactionSynchronization.STATUS_ROLLED_BACK);
            }

            Thread.sleep(150);
            // 验证未产生幽灵报文
            assertThat(broker.getReceivedMessages()).isEmpty();
        } finally {
            TransactionSynchronizationManager.clearSynchronization();
        }
    }

    @Test
    @DisplayName("验证 MqttContextHolder.runWith 支持线程级动态切源至指定客户端")
    void shouldRouteToSpecifiedClientViaContextHolder() throws Exception {
        // 1. 默认发信 -> primary 客户端
        mqttTemplate.publish("test/route/default", "msg-primary");

        // 2. 动态切源至 secondary 客户端发信
        MqttContextHolder.runWith("secondary", () -> {
            mqttTemplate.publish("test/route/secondary", "msg-secondary");
        });

        // 3. 切源退出后恢复至 primary 客户端
        mqttTemplate.publish("test/route/back", "msg-back-to-primary");

        Thread.sleep(200);

        List<EmbeddedMqttBroker.ReceivedMessage> msgs = broker.getReceivedMessages();
        assertThat(msgs).hasSize(3);

        Map<String, String> topicToClientId = new HashMap<>();
        for (EmbeddedMqttBroker.ReceivedMessage msg : msgs) {
            topicToClientId.put(msg.getTopic(), msg.getClientId());
        }

        assertThat(topicToClientId)
                .containsEntry("test/route/default", "test-primary")
                .containsEntry("test/route/secondary", "test-secondary")
                .containsEntry("test/route/back", "test-primary");
    }

    @Test
    @DisplayName("验证 MqttSendFailureHandler 补偿 SPI 在发信异常时被正确触发回调")
    void shouldTriggerFailureHandlerOnPublishError() {
        // 关闭 Broker 模拟物理断网与连接不可达
        broker.stop();
        broker = null; // 标记已关闭，防御 tearDown 重复停止触发 Netty 线程拒绝

        // 显式断开客户端连接，确保测试在任何宿主与并发环境下断网必定确定性快速失败
        try {
            clientRegistry.getClientHandle("primary").getAsyncClient().disconnectForcibly();
        } catch (Throwable ignored) {
        }

        assertThatThrownBy(() -> mqttTemplate.publish("test/failure", "fail-payload", 1))
                .isInstanceOf(RuntimeException.class);

        assertThat(failureRecords).hasSize(1);
        FailureRecord record = failureRecords.get(0);
        assertThat(record.clientName()).isEqualTo("primary");
        assertThat(record.topic()).isEqualTo("test/failure");
        assertThat(new String(record.payload(), StandardCharsets.UTF_8)).isEqualTo("fail-payload");
        assertThat(record.cause()).isNotNull();
    }

    @Test
    @DisplayName("验证 MqttPublishRecord 强类型批量并发聚合投递与有序交付")
    void shouldPublishBatchSynchronously() throws Exception {
        List<MqttPublishRecord> records = List.of(
                MqttPublishRecord.builder().topic("batch/topic1").payload("payload-1").qos(0).build(),
                MqttPublishRecord.builder().topic("batch/topic2").payload("payload-2").qos(1).retained(true).build(),
                MqttPublishRecord.builder().topic("batch/topic3").payload("payload-3").qos(2).build()
        );

        mqttTemplate.publishBatch(records);

        Thread.sleep(250);

        List<EmbeddedMqttBroker.ReceivedMessage> msgs = broker.getReceivedMessages();
        assertThat(msgs).hasSize(3);

        Map<String, EmbeddedMqttBroker.ReceivedMessage> msgMap = new HashMap<>();
        for (EmbeddedMqttBroker.ReceivedMessage msg : msgs) {
            msgMap.put(msg.getTopic(), msg);
        }

        assertThat(msgMap).containsKey("batch/topic1").containsKey("batch/topic2").containsKey("batch/topic3");
        assertThat(new String(msgMap.get("batch/topic1").getPayload(), StandardCharsets.UTF_8)).isEqualTo("payload-1");
        assertThat(msgMap.get("batch/topic1").getQos()).isEqualTo(MqttQoS.AT_MOST_ONCE);
        assertThat(msgMap.get("batch/topic1").isRetained()).isFalse();

        assertThat(new String(msgMap.get("batch/topic2").getPayload(), StandardCharsets.UTF_8)).isEqualTo("payload-2");
        assertThat(msgMap.get("batch/topic2").getQos()).isEqualTo(MqttQoS.AT_LEAST_ONCE);
        assertThat(msgMap.get("batch/topic2").isRetained()).isTrue();

        assertThat(new String(msgMap.get("batch/topic3").getPayload(), StandardCharsets.UTF_8)).isEqualTo("payload-3");
        assertThat(msgMap.get("batch/topic3").getQos()).isEqualTo(MqttQoS.EXACTLY_ONCE);
    }

    @Test
    @DisplayName("验证 MqttPublishRecord 异步批量并发聚合投递")
    void shouldPublishBatchAsynchronously() throws Exception {
        List<MqttPublishRecord> records = List.of(
                MqttPublishRecord.of("async/batch/1", "async-1"),
                MqttPublishRecord.of("async/batch/2", "async-2")
        );

        mqttTemplate.publishBatchAsync(records).join();

        Thread.sleep(200);

        List<EmbeddedMqttBroker.ReceivedMessage> msgs = broker.getReceivedMessages();
        assertThat(msgs).hasSize(2);

        Map<String, String> receivedMap = new HashMap<>();
        for (EmbeddedMqttBroker.ReceivedMessage msg : msgs) {
            receivedMap.put(msg.getTopic(), new String(msg.getPayload(), StandardCharsets.UTF_8));
        }

        assertThat(receivedMap)
                .containsEntry("async/batch/1", "async-1")
                .containsEntry("async/batch/2", "async-2");
    }

    @Test
    @DisplayName("验证 clearRetained 与 clearRetainedAsync 对称清理保留消息")
    void shouldClearRetainedMessage() throws Exception {
        // 1. 同步清理保留消息
        mqttTemplate.clearRetained("test/retained/sym");

        // 2. 异步清理保留消息
        mqttTemplate.clearRetainedAsync("test/retained/sym-async").join();

        Thread.sleep(200);

        List<EmbeddedMqttBroker.ReceivedMessage> msgs = broker.getReceivedMessages();
        assertThat(msgs).hasSize(2);

        EmbeddedMqttBroker.ReceivedMessage msg1 = msgs.get(0);
        assertThat(msg1.getTopic()).isEqualTo("test/retained/sym");
        assertThat(msg1.getPayload()).isEmpty();
        assertThat(msg1.isRetained()).isTrue();
        assertThat(msg1.getQos()).isEqualTo(MqttQoS.AT_LEAST_ONCE);

        EmbeddedMqttBroker.ReceivedMessage msg2 = msgs.get(1);
        assertThat(msg2.getTopic()).isEqualTo("test/retained/sym-async");
        assertThat(msg2.getPayload()).isEmpty();
        assertThat(msg2.isRetained()).isTrue();
        assertThat(msg2.getQos()).isEqualTo(MqttQoS.AT_LEAST_ONCE);
    }

    @Test
    @DisplayName("验证 MQTT 5.0 消息过期时间 (messageExpiry) 与自定义 userProperties 透传")
    void shouldPublishWithCustomMessageExpiryAndUserProperties() throws Exception {
        MqttPublishRecord record = MqttPublishRecord.builder()
                .topic("test/expiry/custom")
                .payload("expiry-payload")
                .qos(1)
                .messageExpiry(Duration.ofSeconds(120))
                .userProperty("custom-key", "custom-value")
                .build();

        mqttTemplate.publishBatch(List.of(record));

        Thread.sleep(200);

        List<EmbeddedMqttBroker.ReceivedMessage> msgs = broker.getReceivedMessages();
        assertThat(msgs).hasSize(1);
        EmbeddedMqttBroker.ReceivedMessage received = msgs.get(0);

        MqttProperties properties = received.getProperties();
        assertThat(properties).isNotNull();

        // 验证自定义 userProperties
        Map<String, String> userProps = new HashMap<>();
        for (MqttProperties.MqttProperty<?> prop : properties.listAll()) {
            if (prop instanceof MqttProperties.UserProperties up) {
                for (MqttProperties.StringPair pair : up.value()) {
                    userProps.put(pair.key, pair.value);
                }
            } else if (prop instanceof MqttProperties.UserProperty up) {
                userProps.put(up.value().key, up.value().value);
            }
        }
        assertThat(userProps).containsEntry("custom-key", "custom-value");

        // 验证 messageExpiryInterval (Netty MqttPropertyType.PUBLICATION_EXPIRY_INTERVAL = 17)
        MqttProperties.MqttProperty<?> expiryProp = properties.getProperty(MqttProperties.MqttPropertyType.PUBLICATION_EXPIRY_INTERVAL.value());
        assertThat(expiryProp).isNotNull();
        assertThat(expiryProp.value()).isEqualTo(120);
    }

    @Test
    @DisplayName("验证 MqttPublishRecord 字段校验守卫与不可变性")
    void shouldValidatePublishRecordFields() {
        // 1. 空 topic 校验
        assertThatThrownBy(() -> MqttPublishRecord.builder().topic("").payload("data").build())
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("topic must not be blank");

        assertThatThrownBy(() -> MqttPublishRecord.builder().payload("data").build())
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("topic must not be blank");

        // 2. 空 payload 校验
        assertThatThrownBy(() -> MqttPublishRecord.builder().topic("t").build())
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("payload must not be null");

        // 3. QoS 超界校验
        assertThatThrownBy(() -> MqttPublishRecord.builder().topic("t").payload("p").qos(3).build())
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("QoS must be between 0 and 2");

        assertThatThrownBy(() -> MqttPublishRecord.builder().topic("t").payload("p").qos(-1).build())
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("QoS must be between 0 and 2");

        // 4. 负消息过期时间校验
        assertThatThrownBy(() -> MqttPublishRecord.builder().topic("t").payload("p").messageExpiry(Duration.ofSeconds(-5)).build())
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("messageExpiry must not be negative");

        // 5. of 快捷工厂与默认值
        MqttPublishRecord defaultRecord = MqttPublishRecord.of("default/topic", "default-payload");
        assertThat(defaultRecord.topic()).isEqualTo("default/topic");
        assertThat(defaultRecord.qos()).isEqualTo(1);
        assertThat(defaultRecord.retained()).isFalse();
        assertThat(defaultRecord.userProperties()).isEmpty();
        assertThat(defaultRecord.messageExpiry()).isNull();

        // 6. userProperties 不可变性
        assertThatThrownBy(() -> defaultRecord.userProperties().put("k", "v"))
                .isInstanceOf(UnsupportedOperationException.class);

        // 7. userProperties 防御性拷贝 (外部修改不影响 Record 内部快照)
        Map<String, String> externalMap = new HashMap<>();
        externalMap.put("k1", "v1");
        MqttPublishRecord defensiveRecord = MqttPublishRecord.of("def/topic", "def-payload", 1, false, externalMap, null);
        externalMap.put("k2", "v2");
        assertThat(defensiveRecord.userProperties()).containsKey("k1");
        assertThat(defensiveRecord.userProperties()).doesNotContainKey("k2");
    }

    @Test
    @DisplayName("验证批量发信在传入 null 或空列表时优雅幂等忽略")
    void shouldHandleEmptyOrNullBatchGracefully() {
        mqttTemplate.publishBatch(null);
        mqttTemplate.publishBatch(List.of());
        mqttTemplate.publishBatchAsync(null).join();
        mqttTemplate.publishBatchAsync(List.of()).join();

        assertThat(broker.getReceivedMessages()).isEmpty();
    }

    @Test
    @DisplayName("验证 use(clientName) 客户端绑定视图与路由优先级：显式参数 > use() 视图 > MqttContextHolder > defaultClient")
    void shouldScopeClientViaUseMethodWithExpectedPrecedence() throws Exception {
        // 1. 空白或 null 客户端名快速失败
        assertThatThrownBy(() -> mqttTemplate.use(null)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> mqttTemplate.use("")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> mqttTemplate.use("   ")).isInstanceOf(IllegalArgumentException.class);

        MqttContextHolder.setClientName("primary");
        try {
            MqttTemplate secondaryTemplate = mqttTemplate.use("secondary");
            assertThat(secondaryTemplate).isNotSameAs(mqttTemplate);

            // 2. use("secondary") 覆盖 MqttContextHolder("primary")，触发 secondary 的 1KB PayloadGuard
            byte[] oversizedForSecondary = new byte[2048];
            assertThatThrownBy(() -> secondaryTemplate.publish("scoped/oversized", oversizedForSecondary))
                    .isInstanceOf(BusinessException.class);

            // 3. 同步、异步、批量与清理保留消息均路由至 secondary (test-secondary)
            secondaryTemplate.publishAsync("scoped/async", "async-msg", 1, false).get();
            secondaryTemplate.publishBatch(List.of(MqttPublishRecord.of("scoped/batch", "batch-msg")));
            secondaryTemplate.clearRetained("scoped/retained");

            // 4. 显式传入 clientName 仍优先于 use() 绑定视图
            secondaryTemplate.publishToClient("primary", "scoped/explicit", "explicit-msg");

            Thread.sleep(200);

            List<EmbeddedMqttBroker.ReceivedMessage> msgs = broker.getReceivedMessages();
            assertThat(msgs).anySatisfy(m -> {
                assertThat(m.getTopic()).isEqualTo("scoped/async");
                assertThat(m.getClientId()).isEqualTo("test-secondary");
            });
            assertThat(msgs).anySatisfy(m -> {
                assertThat(m.getTopic()).isEqualTo("scoped/batch");
                assertThat(m.getClientId()).isEqualTo("test-secondary");
            });
            assertThat(msgs).anySatisfy(m -> {
                assertThat(m.getTopic()).isEqualTo("scoped/retained");
                assertThat(m.getClientId()).isEqualTo("test-secondary");
                assertThat(m.isRetained()).isTrue();
            });
            assertThat(msgs).anySatisfy(m -> {
                assertThat(m.getTopic()).isEqualTo("scoped/explicit");
                assertThat(m.getClientId()).isEqualTo("test-primary");
            });
        } finally {
            MqttContextHolder.clear();
        }
    }

    @Test
    @DisplayName("验证 OfflineBufferPolicy：BUFFER_WHEN_RECONNECTING 断线缓冲重发与 FAIL_FAST 快速失败及 RPC 断线拒收")
    void shouldRespectOfflineBufferPolicyAndKeepRequestReplyFailFast() throws Exception {
        // 1. 构造各自独立的双策略客户端：bufferedClient (默认 BUFFER_WHEN_RECONNECTING) 与 failFastClient (FAIL_FAST)
        AtengMqttProperties policyProps = new AtengMqttProperties();
        MqttClientProperties bufferedProps = new MqttClientProperties();
        bufferedProps.setServerUri(broker.getServerUri());
        bufferedProps.setClientId("offline-buffered");
        bufferedProps.setClientIdStrategy(ClientIdStrategy.STATIC);
        bufferedProps.setAutomaticReconnect(true);
        bufferedProps.setMaxReconnectDelay(Duration.ofSeconds(1));
        bufferedProps.setMaxBufferedMessages(100);
        assertThat(bufferedProps.getOfflineBufferPolicy()).isEqualTo(OfflineBufferPolicy.BUFFER_WHEN_RECONNECTING);

        MqttClientProperties failFastProps = new MqttClientProperties();
        failFastProps.setServerUri(broker.getServerUri());
        failFastProps.setClientId("offline-failfast");
        failFastProps.setClientIdStrategy(ClientIdStrategy.STATIC);
        failFastProps.setAutomaticReconnect(true);
        failFastProps.setMaxReconnectDelay(Duration.ofSeconds(1));
        failFastProps.setOfflineBufferPolicy(OfflineBufferPolicy.FAIL_FAST);

        policyProps.setClients(Map.of(
                "buffered", bufferedProps,
                "failfast", failFastProps
        ));

        NamedMqttClientRegistry isolatedRegistry = new NamedMqttClientRegistry(policyProps);
        isolatedRegistry.initClients();
        MqttRequestReplyManager rpcManager = new MqttRequestReplyManager(isolatedRegistry, payloadGuard, null);
        MqttTemplate policyTemplate = new MqttTemplate(isolatedRegistry, payloadGuard, null, null, rpcManager);

        try {
            MqttClientHandle bufferedHandle = isolatedRegistry.getClientHandle("buffered");
            MqttClientHandle failFastHandle = isolatedRegistry.getClientHandle("failfast");
            assertThat(bufferedHandle.isConnected()).isTrue();
            assertThat(failFastHandle.isConnected()).isTrue();

            // 模拟网络瞬断：关闭 Broker 侧连接通道，进入重连退避窗口
            broker.disconnectAllClients();
            long waitDisconnectDeadline = System.currentTimeMillis() + 3000;
            while (System.currentTimeMillis() < waitDisconnectDeadline
                    && (bufferedHandle.isConnected() || failFastHandle.isConnected())) {
                Thread.sleep(20);
            }
            assertThat(bufferedHandle.isConnected()).isFalse();
            assertThat(failFastHandle.isConnected()).isFalse();

            // 2. FAIL_FAST 策略下同步与异步发信均立即快速失败
            assertThatThrownBy(() -> policyTemplate.use("failfast").publish("offline/failfast", "drop", 1, false))
                    .isInstanceOf(BusinessException.class);
            assertThatThrownBy(() -> policyTemplate.use("failfast").publishAsync("offline/failfast", "drop", 1, false).join())
                    .isInstanceOf(java.util.concurrent.CompletionException.class);

            // 3. BUFFER_WHEN_RECONNECTING 策略下请求-响应仍须快速失败
            assertThatThrownBy(() -> policyTemplate.use("buffered").sendAndReceive("offline/rpc", "ping", Duration.ofSeconds(2)))
                    .isInstanceOf(BusinessException.class);

            // 4. BUFFER_WHEN_RECONNECTING 策略下异步发信进入 DisconnectedBuffer，重连恢复后自动送达 Broker
            java.util.concurrent.CompletableFuture<Void> bufferedFuture =
                    policyTemplate.use("buffered").publishAsync("offline/buffered", "recovered-after-reconnect", 1, false);

            bufferedFuture.get(6, java.util.concurrent.TimeUnit.SECONDS);

            long receiveDeadline = System.currentTimeMillis() + 3000;
            while (System.currentTimeMillis() < receiveDeadline
                    && broker.getReceivedMessages().stream().noneMatch(m -> "offline/buffered".equals(m.getTopic()))) {
                Thread.sleep(30);
            }
            assertThat(broker.getReceivedMessages()).anySatisfy(m -> {
                assertThat(m.getTopic()).isEqualTo("offline/buffered");
                assertThat(new String(m.getPayload(), StandardCharsets.UTF_8)).isEqualTo("recovered-after-reconnect");
            });
        } finally {
            rpcManager.stop();
            isolatedRegistry.stop();
        }
    }

    @Test
    @DisplayName("验证 sendAfterCommit 全链重载：userProperties、messageExpiry、拦截器 pre/postPublish 及回滚取消")
    void shouldSupportUserPropsExpiryInterceptorsAndRollbackInSendAfterCommit() throws Exception {
        List<String> events = new CopyOnWriteArrayList<>();
        io.github.atengk.boot.mqtt.interceptor.MqttMessageInterceptor interceptor =
                new io.github.atengk.boot.mqtt.interceptor.MqttMessageInterceptor() {
                    @Override
                    public boolean prePublish(String clientName, String topic, byte[] payload, int qos, boolean retained, Map<String, String> userProperties) {
                        events.add("pre:" + clientName + ":" + topic);
                        userProperties.put("x-signed", "sig-tx");
                        return !"tx/abort".equals(topic);
                    }

                    @Override
                    public void postPublish(String clientName, String topic, byte[] payload, int qos, boolean retained, Map<String, String> userProperties, Throwable ex) {
                        events.add("post:" + clientName + ":" + topic + ":" + (ex == null ? "ok" : "err"));
                    }
                };

        MqttTemplate txTemplate = new MqttTemplate(clientRegistry, payloadGuard, null, null, null, null, null, List.of(interceptor));

        // 1. 事务提交场景：延迟至 afterCommit 执行拦截器与全属性发信
        TransactionSynchronizationManager.initSynchronization();
        TransactionSynchronizationManager.setActualTransactionActive(true);
        try {
            txTemplate.sendAfterCommit("tx/full-chain", "tx-body", 1, false, Map.of("biz-id", "B001"), Duration.ofSeconds(45));
            txTemplate.use("secondary").sendAfterCommit("tx/scoped-tx", "tx-body-2", 1, false, Duration.ofSeconds(30));
            txTemplate.sendAfterCommit("tx/abort", "abort-body", 1, false, Map.of("k", "v"), Duration.ofSeconds(10));

            assertThat(broker.getReceivedMessages()).isEmpty();
            assertThat(events).isEmpty();

            for (TransactionSynchronization sync : TransactionSynchronizationManager.getSynchronizations()) {
                sync.afterCommit();
            }
            for (TransactionSynchronization sync : TransactionSynchronizationManager.getSynchronizations()) {
                sync.afterCompletion(TransactionSynchronization.STATUS_COMMITTED);
            }
        } finally {
            TransactionSynchronizationManager.clear();
        }

        Thread.sleep(200);

        assertThat(events).contains("pre:primary:tx/full-chain", "post:primary:tx/full-chain:ok", "pre:secondary:tx/scoped-tx", "post:secondary:tx/scoped-tx:ok", "pre:primary:tx/abort");
        assertThat(events).noneMatch(e -> e.startsWith("post:primary:tx/abort"));
        assertThat(broker.getReceivedMessages()).hasSize(2);

        EmbeddedMqttBroker.ReceivedMessage fullMsg = broker.getReceivedMessages().stream()
                .filter(m -> "tx/full-chain".equals(m.getTopic())).findFirst().orElseThrow();
        Map<String, String> headers = new HashMap<>();
        for (MqttProperties.MqttProperty<?> prop : fullMsg.getProperties().listAll()) {
            if (prop instanceof MqttProperties.UserProperties up) {
                for (MqttProperties.StringPair pair : up.value()) {
                    headers.put(pair.key, pair.value);
                }
            } else if (prop instanceof MqttProperties.UserProperty up) {
                headers.put(up.value().key, up.value().value);
            }
        }
        assertThat(headers).containsEntry("biz-id", "B001").containsEntry("x-signed", "sig-tx");
        assertThat(fullMsg.getProperties().getProperty(MqttProperties.MqttPropertyType.PUBLICATION_EXPIRY_INTERVAL.value()).value())
                .isEqualTo(45);

        EmbeddedMqttBroker.ReceivedMessage scopedMsg = broker.getReceivedMessages().stream()
                .filter(m -> "tx/scoped-tx".equals(m.getTopic())).findFirst().orElseThrow();
        assertThat(scopedMsg.getClientId()).isEqualTo("test-secondary");
        assertThat(scopedMsg.getProperties().getProperty(MqttProperties.MqttPropertyType.PUBLICATION_EXPIRY_INTERVAL.value()).value())
                .isEqualTo(30);

        // 2. 事务回滚场景：取消投递且不触发拦截发信
        events.clear();
        broker.clearReceivedMessages();
        TransactionSynchronizationManager.initSynchronization();
        TransactionSynchronizationManager.setActualTransactionActive(true);
        try {
            txTemplate.sendAfterCommitToClient("primary", "tx/rollback-overload", "rb-body", 1, false, Map.of("k", "v"), Duration.ofSeconds(20));
            for (TransactionSynchronization sync : TransactionSynchronizationManager.getSynchronizations()) {
                sync.afterCompletion(TransactionSynchronization.STATUS_ROLLED_BACK);
            }
        } finally {
            TransactionSynchronizationManager.clear();
        }
        assertThat(broker.getReceivedMessages()).isEmpty();
        assertThat(events).isEmpty();
    }

    @Test
    @DisplayName("验证编程式订阅受管化：actualTopic 透传、PayloadGuard、消费拦截器、四维上下文、重连自愈与退订移除")
    void shouldManageProgrammaticSubscriptionsWithActualTopicGuardInterceptorsAndReconnect() throws Exception {
        AtengMqttProperties subProps = new AtengMqttProperties();
        MqttClientProperties senderProps = new MqttClientProperties();
        senderProps.setServerUri(broker.getServerUri());
        senderProps.setClientId("prog-sender");
        senderProps.setClientIdStrategy(ClientIdStrategy.STATIC);
        senderProps.setMaxPayloadSize(DataSize.ofMegabytes(1));

        MqttClientProperties subClientProps = new MqttClientProperties();
        subClientProps.setServerUri(broker.getServerUri());
        subClientProps.setClientId("prog-subscriber");
        subClientProps.setClientIdStrategy(ClientIdStrategy.STATIC);
        subClientProps.setAutomaticReconnect(true);
        subClientProps.setMaxReconnectDelay(Duration.ofSeconds(1));
        subClientProps.setMaxPayloadSize(DataSize.ofKilobytes(1));

        subProps.setClients(Map.of(
                "sender", senderProps,
                "edge", subClientProps
        ));

        NamedMqttClientRegistry subRegistry = new NamedMqttClientRegistry(subProps);
        subRegistry.initClients();

        List<String> consumeTraces = new CopyOnWriteArrayList<>();
        io.github.atengk.boot.mqtt.interceptor.MqttMessageInterceptor consumeInterceptor =
                new io.github.atengk.boot.mqtt.interceptor.MqttMessageInterceptor() {
                    @Override
                    public boolean preConsume(String clientName, String endpointId, String topic, org.eclipse.paho.mqttv5.common.MqttMessage message, Map<String, String> headers) {
                        consumeTraces.add("preConsume:" + clientName + ":" + topic);
                        return !"devices/blocked/telemetry".equals(topic);
                    }

                    @Override
                    public void postConsume(String clientName, String endpointId, String topic, org.eclipse.paho.mqttv5.common.MqttMessage message, Map<String, String> headers, Throwable ex) {
                        consumeTraces.add("postConsume:" + clientName + ":" + topic + ":" + (ex == null ? "ok" : "err"));
                    }
                };

        MqttTemplate managedTemplate = new MqttTemplate(subRegistry, payloadGuard, null, null, null, null, null, List.of(consumeInterceptor));
        List<String> delivered = new CopyOnWriteArrayList<>();

        try {
            // 1. 订阅通配符主题，断言收到真实 actualTopic 而非 topicFilter，且四维上下文自动还原
            managedTemplate.use("edge").subscribe("devices/+/telemetry", 1, (actualTopic, payload) -> {
                String line = actualTopic
                        + "|" + new String(payload, StandardCharsets.UTF_8)
                        + "|trace=" + MDC.get("traceId")
                        + "|tenant=" + TenantContextHolder.getTenantId()
                        + "|user=" + UserContextHolder.get();
                delivered.add(line);
            });

            MDC.put("traceId", "trace-prog-001");
            TenantContextHolder.setTenantId("tenant-prog");
            UserContextHolder.set("user-prog");
            try {
                managedTemplate.use("sender").publish("devices/dev-42/telemetry", "ok-42", 1, false);
                managedTemplate.use("sender").publish("devices/blocked/telemetry", "should-drop", 1, false);
                managedTemplate.use("sender").publish("devices/oversized/telemetry", new byte[2048], 1, false);
            } finally {
                MDC.clear();
                TenantContextHolder.clear();
                UserContextHolder.clear();
            }

            long deadline = System.currentTimeMillis() + 3000;
            while (System.currentTimeMillis() < deadline && delivered.isEmpty()) {
                Thread.sleep(30);
            }
            Thread.sleep(150);

            assertThat(delivered).hasSize(1);
            assertThat(delivered.get(0)).startsWith("devices/dev-42/telemetry|ok-42|trace=trace-prog-001|tenant=tenant-prog|user=user-prog");
            assertThat(consumeTraces).contains("preConsume:edge:devices/dev-42/telemetry", "postConsume:edge:devices/dev-42/telemetry:ok");
            assertThat(consumeTraces).contains("preConsume:edge:devices/blocked/telemetry");
            assertThat(consumeTraces).noneMatch(s -> s.startsWith("postConsume:edge:devices/blocked/telemetry"));

            // 2. 模拟物理断连后自动重连，校验编程式订阅自动自愈恢复
            java.util.concurrent.CountDownLatch edgeHealed = new java.util.concurrent.CountDownLatch(1);
            java.util.concurrent.CountDownLatch senderHealed = new java.util.concurrent.CountDownLatch(1);
            subRegistry.getClientHandle("edge").addConnectCompleteListener((reconnect, uri) -> {
                if (reconnect) {
                    edgeHealed.countDown();
                }
            });
            subRegistry.getClientHandle("sender").addConnectCompleteListener((reconnect, uri) -> {
                if (reconnect) {
                    senderHealed.countDown();
                }
            });

            broker.disconnectAllClients();
            assertThat(edgeHealed.await(6, java.util.concurrent.TimeUnit.SECONDS)).isTrue();
            assertThat(senderHealed.await(6, java.util.concurrent.TimeUnit.SECONDS)).isTrue();
            Thread.sleep(200);
            assertThat(subRegistry.getClientHandle("edge").isConnected()).isTrue();
            assertThat(subRegistry.getClientHandle("sender").isConnected()).isTrue();

            managedTemplate.use("sender").publish("devices/dev-99/telemetry", "after-reconnect", 1, false);
            long healDeadline = System.currentTimeMillis() + 3000;
            while (System.currentTimeMillis() < healDeadline && delivered.size() < 2) {
                Thread.sleep(30);
            }
            assertThat(delivered).anySatisfy(s -> assertThat(s).startsWith("devices/dev-99/telemetry|after-reconnect|"));

            // 3. 退订后不再接收且从受管登记表清理
            int countBeforeUnsub = delivered.size();
            managedTemplate.use("edge").unsubscribe("devices/+/telemetry");
            managedTemplate.use("sender").publish("devices/dev-100/telemetry", "after-unsub", 1, false);
            Thread.sleep(200);
            assertThat(delivered).hasSize(countBeforeUnsub);
        } finally {
            subRegistry.stop();
        }
    }
}
