package io.github.atengk.boot.mqtt.governance;

import io.github.atengk.boot.core.context.TenantContextHolder;
import io.github.atengk.boot.core.context.UserContextHolder;
import io.github.atengk.boot.mqtt.context.MqttContextHolder;
import io.github.atengk.boot.mqtt.defense.PayloadGuard;
import io.github.atengk.boot.mqtt.listener.MqttListenerEndpointRegistry;
import io.github.atengk.boot.mqtt.model.MqttDeadLetterRecord;
import io.github.atengk.boot.mqtt.properties.AtengMqttProperties;
import io.github.atengk.boot.mqtt.properties.ClientIdStrategy;
import io.github.atengk.boot.mqtt.properties.MqttClientProperties;
import io.github.atengk.boot.mqtt.properties.PersistenceType;
import io.github.atengk.boot.mqtt.registry.NamedMqttClientRegistry;
import io.github.atengk.boot.mqtt.template.MqttDeadLetterReplayTemplate;
import io.github.atengk.boot.mqtt.template.MqttTemplate;
import io.github.atengk.boot.mqtt.test.EmbeddedMqttBroker;
import org.eclipse.paho.mqttv5.common.MqttMessage;
import org.eclipse.paho.mqttv5.common.MqttSubscription;
import org.eclipse.paho.mqttv5.common.packet.MqttProperties;
import org.eclipse.paho.mqttv5.common.packet.UserProperty;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;
import org.springframework.util.unit.DataSize;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * MQTT 死信受控治理、只读探查 (peek)、受控重放 (replay)、清空 (purge) 与防二次雪崩熔断测试
 *
 * @author Ateng
 * @since 2026-10-31
 */
class MqttDeadLetterGovernanceTest {

    private EmbeddedMqttBroker broker;
    private NamedMqttClientRegistry clientRegistry;
    private MqttTemplate mqttTemplate;
    private MqttDeadLetterReplayTemplate dltReplayTemplate;
    private ExecutorService executor;

    @BeforeEach
    void setUp() {
        // 1. 启动轻量纯内存 MQTT Broker
        broker = new EmbeddedMqttBroker(0);
        broker.start();
        executor = Executors.newCachedThreadPool();

        // 2. 配置主客户端
        AtengMqttProperties properties = new AtengMqttProperties();
        properties.setDefaultClient("primary");

        Map<String, MqttClientProperties> clientMap = new HashMap<>();
        MqttClientProperties primary = new MqttClientProperties();
        primary.setServerUri(broker.getServerUri());
        primary.setClientId("dlt-test-primary");
        primary.setClientIdStrategy(ClientIdStrategy.STATIC);
        primary.setCleanStart(true);
        primary.setConnectionTimeout(Duration.ofSeconds(3));
        primary.setMaxPayloadSize(DataSize.ofMegabytes(1));
        primary.setPersistenceType(PersistenceType.MEMORY);
        primary.setProtocolVersion("5.0");
        clientMap.put("primary", primary);

        properties.setClients(clientMap);
        clientRegistry = new NamedMqttClientRegistry(properties);
        clientRegistry.initClients();

        PayloadGuard payloadGuard = new PayloadGuard();
        mqttTemplate = new MqttTemplate(clientRegistry, payloadGuard, null, executor);
        dltReplayTemplate = new MqttDeadLetterReplayTemplate(clientRegistry, mqttTemplate);
    }

    @AfterEach
    void tearDown() {
        MDC.clear();
        TenantContextHolder.clear();
        UserContextHolder.clear();
        MqttContextHolder.clear();

        if (clientRegistry != null) {
            clientRegistry.destroy();
        }
        if (executor != null) {
            executor.shutdownNow();
        }
        if (broker != null) {
            broker.stop();
        }
    }

    @Test
    @DisplayName("只读探查 (peek)：探查死信主题报文与错误堆栈元数据，不干扰报文状态")
    void shouldPeekDeadLetterRecordsWithoutAffectingMessages() throws Exception {
        String dltTopic = "sensor/telemetry/data/DLT";

        // 向死信主题发布一条保留死信报文（附加死信元数据标头）
        Map<String, String> headers = Map.of(
                MqttDeadLetterRecord.HEADER_ORIGINAL_TOPIC, "sensor/telemetry/data",
                MqttDeadLetterRecord.HEADER_EXCEPTION_CLASS, "java.lang.IllegalArgumentException",
                MqttDeadLetterRecord.HEADER_EXCEPTION_MESSAGE, "Invalid voltage reading: -12.5V",
                MqttDeadLetterRecord.HEADER_DLT_TIMESTAMP, String.valueOf(System.currentTimeMillis())
        );

        mqttTemplate.publishToClient("primary", dltTopic, "MALFORMED_TELEMETRY_PAYLOAD", 1, true, headers);

        Thread.sleep(200);

        // 执行只读探查
        List<MqttDeadLetterRecord> records = dltReplayTemplate.peek(dltTopic, 5);

        assertThat(records).hasSize(1);
        MqttDeadLetterRecord record = records.get(0);
        assertThat(record.dltTopic()).isEqualTo(dltTopic);
        assertThat(record.originalTopic()).isEqualTo("sensor/telemetry/data");
        assertThat(record.getPayloadAsString()).isEqualTo("MALFORMED_TELEMETRY_PAYLOAD");
        assertThat(record.exceptionClass()).isEqualTo("java.lang.IllegalArgumentException");
        assertThat(record.exceptionMessage()).isEqualTo("Invalid voltage reading: -12.5V");
        assertThat(record.replayCount()).isEqualTo(0);
        assertThat(record.isReplayed()).isFalse();
    }

    @Test
    @DisplayName("受控重放 (replay)：清洗原始异常标头，注入全链路重放元数据并回放至目标业务主题")
    void shouldReplayDeadLetterRecordWithHeaderCleaningAndReplayMetadata() throws Exception {
        String dltTopic = "order/payment/notify/DLT";
        String targetTopic = "order/payment/notify/replayed";

        // 1. 发布一条待重放的死信
        Map<String, String> errorHeaders = Map.of(
                MqttDeadLetterRecord.HEADER_ORIGINAL_TOPIC, "order/payment/notify",
                MqttDeadLetterRecord.HEADER_EXCEPTION_CLASS, "java.lang.NullPointerException",
                MqttDeadLetterRecord.HEADER_EXCEPTION_MESSAGE, "MerchantId missing in payload",
                MqttDeadLetterRecord.HEADER_DLT_TIMESTAMP, String.valueOf(System.currentTimeMillis()),
                "biz-trace-tag", "PAY-ORDER-9999"
        );
        mqttTemplate.publishToClient("primary", dltTopic, "ORDER_PAY_RAW_PAYLOAD", 1, true, errorHeaders);
        Thread.sleep(200);

        // 2. 模拟订阅目标重放主题以验证交付
        CountDownLatch replayLatch = new CountDownLatch(1);
        List<MqttMessage> receivedReplays = new CopyOnWriteArrayList<>();
        MqttSubscription sub = new MqttSubscription(targetTopic, 1);
        clientRegistry.getDefaultClientHandle().getAsyncClient().subscribe(
                new MqttSubscription[]{sub}, null, null,
                new org.eclipse.paho.mqttv5.client.IMqttMessageListener[]{
                        (topic, message) -> {
                            receivedReplays.add(message);
                            replayLatch.countDown();
                        }
                }, null
        ).waitForCompletion(3000);

        // 3. 执行受控重放
        int count = dltReplayTemplate.replay(dltTopic, targetTopic, 5);
        assertThat(count).isEqualTo(1);

        boolean received = replayLatch.await(3, TimeUnit.SECONDS);
        assertThat(received).isTrue();
        assertThat(receivedReplays).hasSize(1);

        MqttMessage replayedMsg = receivedReplays.get(0);
        assertThat(new String(replayedMsg.getPayload(), StandardCharsets.UTF_8)).isEqualTo("ORDER_PAY_RAW_PAYLOAD");

        // 4. 验证标头清洗与元数据注入
        MqttProperties props = replayedMsg.getProperties();
        assertThat(props).isNotNull();

        Map<String, String> userProps = new HashMap<>();
        for (UserProperty up : props.getUserProperties()) {
            userProps.put(up.getKey(), up.getValue());
        }

        // 原始死信异常标头已被彻底剔除
        assertThat(userProps).doesNotContainKey(MqttDeadLetterRecord.HEADER_ORIGINAL_TOPIC);
        assertThat(userProps).doesNotContainKey(MqttDeadLetterRecord.HEADER_EXCEPTION_CLASS);
        assertThat(userProps).doesNotContainKey(MqttDeadLetterRecord.HEADER_EXCEPTION_MESSAGE);
        assertThat(userProps).doesNotContainKey(MqttDeadLetterRecord.HEADER_DLT_TIMESTAMP);

        // 业务自定义非异常标头保留
        assertThat(userProps).containsEntry("biz-trace-tag", "PAY-ORDER-9999");

        // 注入了全新的重放追踪标头
        assertThat(userProps).containsEntry(MqttDeadLetterRecord.HEADER_REPLAYED, "true");
        assertThat(userProps).containsEntry(MqttDeadLetterRecord.HEADER_REPLAY_COUNT, "1");
        assertThat(userProps).containsKey(MqttDeadLetterRecord.HEADER_REPLAY_TIMESTAMP);
    }

    @Test
    @DisplayName("防二次雪崩熔断：单条记录已达到 maxReplayCount 阈值时拒绝重复重放")
    void shouldCircuitBreakSecondaryAvalancheWhenReplayCountExceedsLimit() {
        MqttDeadLetterRecord alreadyReplayed = new MqttDeadLetterRecord(
                "test/dlt",
                "test/original",
                "POISON_DATA".getBytes(StandardCharsets.UTF_8),
                1,
                "java.lang.RuntimeException",
                "Deadly poison pill",
                System.currentTimeMillis(),
                1, // 已重放过 1 次
                Map.of(MqttDeadLetterRecord.HEADER_REPLAY_COUNT, "1")
        );

        // 默认 maxReplayCount = 1，达到上限后拒绝重放
        boolean success = dltReplayTemplate.replayRecord(alreadyReplayed, "test/original");
        assertThat(success).isFalse();

        // 调整 maxReplayCount = 2，允许已重放 1 次的报文继续重放
        dltReplayTemplate.setMaxReplayCount(2);
        assertThat(dltReplayTemplate.getMaxReplayCount()).isEqualTo(2);
        boolean successWith2 = dltReplayTemplate.replayRecord(alreadyReplayed, "test/original");
        assertThat(successWith2).isTrue();
        dltReplayTemplate.setMaxReplayCount(1); // 还原

        // 验证 limit 分层防卫硬上限 (<= 1000)
        assertThatThrownBy(() -> dltReplayTemplate.peek("test/dlt", 1001))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("limit must not exceed maximum threshold of 1000");
        assertThatThrownBy(() -> dltReplayTemplate.peek("test/dlt", 0))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("防二次雪崩熔断：已重放报文再次消费崩溃时直接丢弃，阻断死信主题自循环死锁")
    void shouldDropSecondaryPoisonPillInListenerRegistryToBreakInfiniteLoop() throws Exception {
        CountDownLatch dltDrainLatch = new CountDownLatch(1);
        List<MqttMessage> dltReceived = new CopyOnWriteArrayList<>();

        // 订阅死信主题
        MqttSubscription dltSub = new MqttSubscription("sensor/poison/replayed/DLT", 1);
        clientRegistry.getDefaultClientHandle().getAsyncClient().subscribe(
                new MqttSubscription[]{dltSub}, null, null,
                new org.eclipse.paho.mqttv5.client.IMqttMessageListener[]{
                        (topic, message) -> {
                            dltReceived.add(message);
                            dltDrainLatch.countDown();
                        }
                }, null
        ).waitForCompletion(3000);

        // 模拟创建带有 X-Replay-Count: 1 的再次失败报文
        MqttProperties props = new MqttProperties();
        props.setUserProperties(List.of(
                new UserProperty(MqttDeadLetterRecord.HEADER_REPLAY_COUNT, "1"),
                new UserProperty(MqttDeadLetterRecord.HEADER_REPLAYED, "true")
        ));
        MqttMessage replayedFailMsg = new MqttMessage("POISON_AGAIN".getBytes(StandardCharsets.UTF_8));
        replayedFailMsg.setQos(1);
        replayedFailMsg.setProperties(props);

        // 验证 MqttDeadLetterRecord.extractReplayCount 能准确识别重放次数
        int extracted = MqttDeadLetterRecord.extractReplayCount(replayedFailMsg);
        assertThat(extracted).isEqualTo(1);
    }

    @Test
    @DisplayName("死信清空抹除 (purge)：抹除保留死信并排空在途瞬态报文")
    void shouldPurgeDeadLetterTopic() throws Exception {
        String dltTopic = "device/alarm/purge/DLT";

        // 发布一条保留死信
        mqttTemplate.publishRetained(dltTopic, "STALE_RETAINED_DEAD_LETTER", 1);
        Thread.sleep(150);

        // 验证确实存在死信
        List<MqttDeadLetterRecord> before = dltReplayTemplate.peek(dltTopic, 1);
        assertThat(before).hasSize(1);

        // 执行 purge
        dltReplayTemplate.purge(dltTopic);
        Thread.sleep(150);

        // 再次探查，验证已彻底抹除清空
        List<MqttDeadLetterRecord> after = dltReplayTemplate.peek(dltTopic, 1);
        assertThat(after).isEmpty();
    }

    @Test
    @DisplayName("模型健壮性：MqttDeadLetterRecord 字段自愈与解包行为健全")
    void shouldValidateDeadLetterRecordModel() {
        // 1. 默认降级原始主题（剥离 /DLT）
        MqttMessage rawMsg = new MqttMessage("RAW_BYTES".getBytes(StandardCharsets.UTF_8));
        rawMsg.setQos(1);

        MqttDeadLetterRecord record = MqttDeadLetterRecord.fromMqttMessage("telemetry/metrics/DLT", rawMsg);
        assertThat(record.originalTopic()).isEqualTo("telemetry/metrics");
        assertThat(record.getPayloadAsString()).isEqualTo("RAW_BYTES");
        assertThat(record.isReplayed()).isFalse();
        assertThat(record.replayCount()).isEqualTo(0);

        // 2. 空保护
        MqttDeadLetterRecord emptyRecord = new MqttDeadLetterRecord(
                "t/DLT", null, null, 1, null, null, 0L, 0, null
        );
        assertThat(emptyRecord.payload()).isEmpty();
        assertThat(emptyRecord.userProperties()).isEmpty();
        assertThat(emptyRecord.getPayloadAsString()).isEmpty();
    }
}
