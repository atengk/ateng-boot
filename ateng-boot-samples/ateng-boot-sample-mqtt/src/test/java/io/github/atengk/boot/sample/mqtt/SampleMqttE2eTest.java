package io.github.atengk.boot.sample.mqtt;

import io.github.atengk.boot.core.context.TenantContextHolder;
import io.github.atengk.boot.core.context.UserContextHolder;
import io.github.atengk.boot.mqtt.actuator.MqttHealthIndicator;
import io.github.atengk.boot.mqtt.codec.HexPayload;
import io.github.atengk.boot.mqtt.model.MqttDeadLetterRecord;
import io.github.atengk.boot.mqtt.model.MqttPublishRecord;
import io.github.atengk.boot.sample.mqtt.model.CommandResponse;
import io.github.atengk.boot.sample.mqtt.model.DeviceTelemetry;
import io.github.atengk.boot.sample.mqtt.service.*;
import io.github.atengk.boot.sample.mqtt.test.EmbeddedMqttBroker;
import io.micrometer.core.instrument.MeterRegistry;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.actuate.health.Health;
import org.springframework.boot.actuate.health.Status;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

/**
 * ateng-boot-sample-mqtt 端到端自动化集成自验用例
 * 严格遵从 Zero-Controller Invariant，纯净基于 @SpringBootTest 验证 7 大场景业务服务
 * 依托纯内存 Netty MQTT Broker 测试桩，100% 离线秒级全绿验证全特性
 *
 * @author Ateng
 * @since 2026-10-31
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
class SampleMqttE2eTest {

    private static EmbeddedMqttBroker broker;

    @BeforeAll
    static void startBroker() {
        broker = new EmbeddedMqttBroker(0);
        broker.start();
    }

    @AfterAll
    static void stopBroker() {
        if (broker != null) {
            broker.stop();
        }
    }

    @DynamicPropertySource
    static void configureProperties(DynamicPropertyRegistry registry) {
        registry.add("ateng.boot.mqtt.clients.primary.server-uri", () -> broker.getServerUri());
    }

    @Autowired
    private DeviceTelemetryScenarioService telemetryScenarioService;

    @Autowired
    private DeviceControlRpcScenarioService rpcScenarioService;

    @Autowired
    private IndustrialHexScenarioService hexScenarioService;

    @Autowired
    private BatchPublishScenarioService batchScenarioService;

    @Autowired
    private DeadLetterReplayScenarioService dltScenarioService;

    @Autowired
    private MqttContainerGovernanceScenarioService governanceScenarioService;

    @Autowired
    private TransactionalTelemetryScenarioService txScenarioService;

    @Autowired(required = false)
    private MqttHealthIndicator healthIndicator;

    @Autowired
    private MeterRegistry meterRegistry;

    @BeforeEach
    void setUp() {
        telemetryScenarioService.clear();
        hexScenarioService.clear();
        batchScenarioService.clear();
        dltScenarioService.clear();
        governanceScenarioService.clear();
        txScenarioService.clear();
        UserContextHolder.clear();
        TenantContextHolder.clear();
    }

    @Test
    @DisplayName("场景 1：设备遥测上行发布与通配符变量、4D 上下文透传、全量标头 Map 注入与共享订阅")
    void testScenario1_DeviceTelemetry() {
        String deviceId = "sensor-iot-99";
        Map<String, String> customHeaders = Map.of(
                "x-trace-id", "trace-sample-001",
                "x-device-firmware", "v2.1.0"
        );

        // 1. 设置 4D 上下文现场并发布遥测数据（附带自定义标头）
        TenantContextHolder.setTenantId("8888");
        UserContextHolder.setUserId("9999");
        telemetryScenarioService.publishTelemetryWithHeaders(deviceId, 28.5, 62.0, customHeaders);

        // 2. 异步断言消费者成功接收并正确解析
        await().atMost(5, TimeUnit.SECONDS).untilAsserted(() -> {
            assertThat(telemetryScenarioService.getReceivedTelemetries()).hasSize(1);
            DeviceTelemetry telemetry = telemetryScenarioService.getReceivedTelemetries().get(0);
            assertThat(telemetry.getDeviceId()).isEqualTo(deviceId);
            assertThat(telemetry.getTemperature()).isEqualTo(28.5);
            assertThat(telemetry.getHumidity()).isEqualTo(62.0);

            // 主题通配提取与标头注入断言
            assertThat(telemetryScenarioService.getReceivedTopic(deviceId)).isEqualTo("iot/devices/" + deviceId + "/telemetry");
            assertThat(telemetryScenarioService.getReceivedVariable(deviceId)).isEqualTo(deviceId);
            assertThat(telemetryScenarioService.getLastTraceId()).isEqualTo("trace-sample-001");

            // 全量标头 Map 注入断言
            Map<String, Object> headers = telemetryScenarioService.getReceivedHeaders(deviceId);
            assertThat(headers).isNotNull();
            assertThat(headers.get("x-device-firmware")).isEqualTo("v2.1.0");

            // 4D 上下文还原断言
            assertThat(telemetryScenarioService.getLastTenantId()).isEqualTo("8888");
            assertThat(telemetryScenarioService.getLastUserId()).isEqualTo("9999");
        });

        // 3. 验证集群共享订阅 $share/telemetry-worker/...
        String metricJson = "{\"cluster\":\"node-1\",\"load\":0.42}";
        telemetryScenarioService.publishClusterMetric(metricJson);

        await().atMost(5, TimeUnit.SECONDS).untilAsserted(() -> {
            assertThat(telemetryScenarioService.getReceivedClusterMetrics()).contains(metricJson);
        });
    }

    @Test
    @DisplayName("场景 2：MQTT 5.0 同步与异步 RPC 请求-响应下行控制闭环")
    void testScenario2_DeviceControlRpc() throws Exception {
        // 1. 同步 RPC 调用
        CommandResponse syncResp = rpcScenarioService.sendControlSync("RESTART_DAEMON", 150, Duration.ofSeconds(5));
        assertThat(syncResp).isNotNull();
        assertThat(syncResp.isSuccess()).isTrue();
        assertThat(syncResp.getMessage()).isEqualTo("ACTION_EXECUTED_SUCCESS");

        // 2. 异步 RPC 调用
        CompletableFuture<CommandResponse> asyncFuture = rpcScenarioService.sendControlAsync("CALIBRATE_SENSOR", 200, Duration.ofSeconds(5));
        CommandResponse asyncResp = asyncFuture.get(5, TimeUnit.SECONDS);
        assertThat(asyncResp).isNotNull();
        assertThat(asyncResp.isSuccess()).isTrue();
        assertThat(asyncResp.getMessage()).isEqualTo("ACTION_EXECUTED_SUCCESS");
    }

    @Test
    @DisplayName("场景 3：工业国标与车联网十六进制报文（HexPayload）双向转译")
    void testScenario3_IndustrialHex() {
        String hexString = "68AAAAAAAAAAAA6811043333333316";
        String deviceId = "meter-power-01";

        hexScenarioService.publishHex(deviceId, hexString);

        await().atMost(5, TimeUnit.SECONDS).untilAsserted(() -> {
            assertThat(hexScenarioService.getReceivedHexPayloads()).hasSize(1);
            HexPayload payload = hexScenarioService.getReceivedHexPayloads().get(0);
            assertThat(payload.getHexString().toUpperCase()).isEqualTo(hexString.toUpperCase());
            assertThat(payload.getBytes().length).isEqualTo(15);
            assertThat(hexScenarioService.getReceivedHexDeviceIds()).contains(deviceId);
        });
    }

    @Test
    @DisplayName("场景 4：批量发信契约聚合投递与保留消息对称清理")
    void testScenario4_BatchAndRetained() {
        // 1. 批量并发投递
        List<MqttPublishRecord> batch = List.of(
                MqttPublishRecord.of("iot/devices/dev-A/batch", "BATCH_PAYLOAD_A", 1),
                MqttPublishRecord.of("iot/devices/dev-B/batch", "BATCH_PAYLOAD_B", 1)
        );
        batchScenarioService.publishBatch(batch);

        await().atMost(5, TimeUnit.SECONDS).untilAsserted(() -> {
            assertThat(batchScenarioService.getReceivedBatchMessages())
                    .contains("BATCH_PAYLOAD_A", "BATCH_PAYLOAD_B");
        });

        // 2. 保留消息发布与清理
        batchScenarioService.publishRetainedStatus("dev-status-1", "ONLINE");
        await().atMost(5, TimeUnit.SECONDS).untilAsserted(() -> {
            assertThat(batchScenarioService.getReceivedStatusMessages()).contains("ONLINE");
        });

        // 对称清理保留消息
        batchScenarioService.clearRetainedStatus("dev-status-1");
    }

    @Test
    @DisplayName("场景 5：毒丸死信（DLT）隔离、只读探查与受控重放治理")
    void testScenario5_DeadLetterReplay() {
        String dltTopic = "iot/devices/poison-test/DLT";
        String targetRecoverTopic = "iot/devices/poison-recovered";

        // 1. 发送毒丸消息，端点抛出致命异常并自动转移至 DLT
        dltScenarioService.triggerPoisonPill("POISON_PAYLOAD");

        // 2. 只读探查 DLT 积压
        await().atMost(5, TimeUnit.SECONDS).untilAsserted(() -> {
            List<MqttDeadLetterRecord> records = dltScenarioService.peekDeadLetters(dltTopic, 10, Duration.ofSeconds(2));
            assertThat(records).isNotEmpty();
            assertThat(records.get(0).getPayloadAsString()).isEqualTo("POISON_PAYLOAD");
            assertThat(records.get(0).exceptionClass()).contains("IllegalArgumentException");
        });

        // 3. 受控重放至恢复主题
        int replayed = dltScenarioService.replayDeadLetters(dltTopic, targetRecoverTopic);
        assertThat(replayed).isGreaterThanOrEqualTo(1);

        // 4. 恢复端点成功接收重放消息
        await().atMost(5, TimeUnit.SECONDS).untilAsserted(() -> {
            assertThat(dltScenarioService.getRecoveredMessages()).contains("POISON_PAYLOAD");
        });

        // 5. 清理 DLT 主题积压
        dltScenarioService.purgeDeadLetters(dltTopic);
    }

    @Test
    @DisplayName("场景 6：运行期消费监听容器动态运维（pause/resume/status）与削峰流控")
    void testScenario6_ContainerGovernance() throws Exception {
        String endpointId = "mqttContainerGovernanceScenarioService#onGovernanceMessage";

        // 1. 验证端点初始为运行状态
        assertThat(governanceScenarioService.isPaused(endpointId)).isFalse();

        // 2. 暂停端点
        boolean paused = governanceScenarioService.pauseEndpoint(endpointId);
        assertThat(paused).isTrue();
        assertThat(governanceScenarioService.isPaused(endpointId)).isTrue();

        // 3. 在暂停期间发信，消息被静默跳过不处理
        governanceScenarioService.publishMessage("MESSAGE_DURING_PAUSE");
        Thread.sleep(300);
        assertThat(governanceScenarioService.getReceivedMessages()).isEmpty();

        // 4. 恢复端点
        boolean resumed = governanceScenarioService.resumeEndpoint(endpointId);
        assertThat(resumed).isTrue();
        assertThat(governanceScenarioService.isPaused(endpointId)).isFalse();

        // 5. 恢复后发信，正常分发消费
        governanceScenarioService.publishMessage("MESSAGE_AFTER_RESUME");
        await().atMost(5, TimeUnit.SECONDS).untilAsserted(() -> {
            assertThat(governanceScenarioService.getReceivedMessages()).contains("MESSAGE_AFTER_RESUME");
        });

        // 6. 验证全景运维状态
        assertThat(governanceScenarioService.getEndpointStatuses()).isNotEmpty();
    }

    @Test
    @DisplayName("场景 7：本地事务提交后发信（sendAfterCommit）与回滚安全丢弃")
    void testScenario7_TransactionalTelemetry() throws Exception {
        // 1. 事务提交：DB 记录递增，消息在事务提交后成功投递
        txScenarioService.recordTelemetryWithCommit("tx-device-commit", 35.0, 50.0);
        assertThat(txScenarioService.getDbRecordsCount()).isEqualTo(1);

        await().atMost(5, TimeUnit.SECONDS).untilAsserted(() -> {
            assertThat(txScenarioService.getReceivedTxTelemetries())
                    .anyMatch(t -> "tx-device-commit".equals(t.getDeviceId()));
        });

        // 2. 事务回滚：DB 记录递增，但已注册的 sendAfterCommit 钩子被安全丢弃，Broker 不收到
        txScenarioService.recordTelemetryWithRollback("tx-device-rollback", 36.0, 51.0);
        assertThat(txScenarioService.getDbRecordsCount()).isEqualTo(2);

        Thread.sleep(400);
        assertThat(txScenarioService.getReceivedTxTelemetries())
                .noneMatch(t -> "tx-device-rollback".equals(t.getDeviceId()));

        // 3. 无事务降级：直接发信
        txScenarioService.recordTelemetryWithoutTx("tx-device-notx", 37.0, 52.0);
        await().atMost(5, TimeUnit.SECONDS).untilAsserted(() -> {
            assertThat(txScenarioService.getReceivedTxTelemetries())
                    .anyMatch(t -> "tx-device-notx".equals(t.getDeviceId()));
        });
    }

    @Test
    @DisplayName("可观测性：Actuator 生产健康感知探针与 Micrometer 黄金度量指标")
    void testObservability() {
        // 先行触发一次发信，确保发布与接收度量指标均有记录
        telemetryScenarioService.publishTelemetry("obs-device-01", 20.0, 45.0);
        await().atMost(5, TimeUnit.SECONDS).untilAsserted(() -> {
            assertThat(telemetryScenarioService.getReceivedTelemetries())
                    .anyMatch(t -> "obs-device-01".equals(t.getDeviceId()));
        });

        // 1. 验证 Actuator HealthIndicator
        if (healthIndicator != null) {
            Health health = healthIndicator.health();
            assertThat(health.getStatus()).isEqualTo(Status.UP);
            assertThat(health.getDetails()).containsKey("clients");
        }

        // 2. 验证 Micrometer 度量指标
        assertThat(meterRegistry.find("mqtt.client.status")
                .tag("client", "primary")
                .gauge())
                .isNotNull()
                .satisfies(gauge -> assertThat(gauge.value()).isEqualTo(1.0));

        assertThat(meterRegistry.find("mqtt.message.published").counters()).isNotEmpty();
        assertThat(meterRegistry.find("mqtt.publish.latency").timers()).isNotEmpty();
        assertThat(meterRegistry.find("mqtt.message.received").counters()).isNotEmpty();
    }
}
