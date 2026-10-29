package io.github.atengk.boot.mqtt.template;

import io.github.atengk.boot.core.context.TenantContextHolder;
import io.github.atengk.boot.core.context.UserContextHolder;
import io.github.atengk.boot.core.exception.BusinessException;
import io.github.atengk.boot.mqtt.context.MqttContextHolder;
import io.github.atengk.boot.mqtt.defense.PayloadGuard;
import io.github.atengk.boot.mqtt.handler.MqttSendFailureHandler;
import io.github.atengk.boot.mqtt.properties.AtengMqttProperties;
import io.github.atengk.boot.mqtt.properties.ClientIdStrategy;
import io.github.atengk.boot.mqtt.properties.MqttClientProperties;
import io.github.atengk.boot.mqtt.properties.PersistenceType;
import io.github.atengk.boot.mqtt.registry.NamedMqttClientRegistry;
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

        assertThatThrownBy(() -> mqttTemplate.publish("test/failure", "fail-payload"))
                .isInstanceOf(RuntimeException.class);

        assertThat(failureRecords).hasSize(1);
        FailureRecord record = failureRecords.get(0);
        assertThat(record.clientName()).isEqualTo("primary");
        assertThat(record.topic()).isEqualTo("test/failure");
        assertThat(new String(record.payload(), StandardCharsets.UTF_8)).isEqualTo("fail-payload");
        assertThat(record.cause()).isNotNull();
    }
}
