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
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.actuate.health.Health;
import org.springframework.boot.actuate.health.Status;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.annotation.DirtiesContext;
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
 * 纯净基于 @SpringBootTest 验证 8 大场景业务服务
 * 依托纯内存 Netty MQTT Broker 测试桩，100% 离线秒级全绿验证全特性
 *
 * @author Ateng
 * @since 2026-10-31
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class SampleMqttE2eTest {

    private static EmbeddedMqttBroker broker;

    private static boolean isLocalProfileActive() {
        String activeProfiles = System.getProperty("spring.profiles.active");
        if (activeProfiles != null && activeProfiles.contains("local")) {
            return true;
        }
        String envProfiles = System.getenv("SPRING_PROFILES_ACTIVE");
        return envProfiles != null && envProfiles.contains("local");
    }

    @BeforeAll
    static void startBroker() {
        if (!isLocalProfileActive()) {
            broker = new EmbeddedMqttBroker(0);
            broker.start();
        }
    }

    @AfterAll
    static void stopBroker() {
        if (broker != null) {
            broker.stop();
        }
    }

    @DynamicPropertySource
    static void configureProperties(DynamicPropertyRegistry registry) {
        if (!isLocalProfileActive()) {
            registry.add("ateng.boot.mqtt.clients.primary.server-uri", () -> broker.getServerUri());
        }
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

    @Autowired
    private ClientGovernanceScenarioService clientGovernanceScenarioService;

    @Autowired(required = false)
    private MqttHealthIndicator healthIndicator;

    @Autowired
    private MeterRegistry meterRegistry;

    @BeforeEach
    @AfterEach
    void resetContextAndScenarioState() {
        telemetryScenarioService.clear();
        hexScenarioService.clear();
        batchScenarioService.clear();
        dltScenarioService.clear();
        governanceScenarioService.clear();
        txScenarioService.clear();
        UserContextHolder.clear();
        TenantContextHolder.clear();
        MDC.clear();
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

    @Test
    @DisplayName("场景 8：多客户端动态治理、MqttTemplate.use() 定向投递与消费、Gauge 注册与注销清理闭环")
    void testScenario8_DynamicClientGovernanceAndGaugeCleanup() {
        String dynamicClient = "park-edge-e2e";
        clientGovernanceScenarioService.clear(dynamicClient);

        var summary = clientGovernanceScenarioService.registerClient(
                io.github.atengk.boot.sample.mqtt.dto.RegisterClientRequest.builder()
                        .clientName(dynamicClient)
                        .clientIdPrefix("e2e-park-edge")
                        .cleanStart(true)
                        .connectionTimeoutSeconds(5)
                        .build()
        );
        assertThat(summary.getClientName()).isEqualTo(dynamicClient);
        assertThat(summary.isConnected()).isTrue();

        try {
            assertThat(meterRegistry.find("mqtt.client.status").tag("client", dynamicClient).gauge())
                    .isNotNull()
                    .satisfies(g -> assertThat(g.value()).isEqualTo(1.0));

            clientGovernanceScenarioService.publishToClient(
                    dynamicClient,
                    "iot/clients/" + dynamicClient + "/command",
                    "E2E_GATE_SYNC",
                    1,
                    false
            );

            await().atMost(5, TimeUnit.SECONDS).untilAsserted(() ->
                    assertThat(clientGovernanceScenarioService.getConsumedMessages(dynamicClient))
                            .contains("E2E_GATE_SYNC")
            );
        } finally {
            clientGovernanceScenarioService.unregisterClient(dynamicClient, false);
        }

        assertThat(meterRegistry.find("mqtt.client.status").tag("client", dynamicClient).gauge()).isNull();
        assertThat(clientGovernanceScenarioService.listClientSummaries())
                .noneMatch(item -> dynamicClient.equals(item.getClientName()));
    }

    @Test
    @DisplayName("场景 9：声明式 @MqttReply、@Valid 死信与有序多毒丸、MANUAL/重试、引用计数流控及 noLocal/retainHandling=2 全链路")
    void testScenario9_EnterpriseReliabilityAndFlowControlE2e() throws Exception {
        // 1. 声明式 @MqttReply 同步与 CompletableFuture 异步应答
        CommandResponse syncReply = rpcScenarioService.sendDeclarativeSync("E2E_DECL_SYNC", 1200, Duration.ofSeconds(3));
        assertThat(syncReply.isSuccess()).isTrue();
        assertThat(syncReply.getMessage()).isEqualTo("DECLARATIVE_SYNC_OK");

        CommandResponse asyncReply = rpcScenarioService.sendDeclarativeAsync("E2E_DECL_ASYNC", 1500, Duration.ofSeconds(3))
                .get(3, TimeUnit.SECONDS);
        assertThat(asyncReply.isSuccess()).isTrue();
        assertThat(asyncReply.getMessage()).isEqualTo("DECLARATIVE_ASYNC_OK");

        // 2. @Valid 校验失败 0 重试入死信 & 连续多条毒丸有序留存
        String validDltTopic = "iot/devices/valid-order/DLT";
        dltScenarioService.purgeDeadLetters(validDltTopic);
        dltScenarioService.triggerInvalidWorkOrder("gw-south-e2e", "", -1);
        await().atMost(5, TimeUnit.SECONDS).untilAsserted(() ->
                assertThat(dltScenarioService.peekDeadLetters(validDltTopic, 5, Duration.ofSeconds(2)))
                        .singleElement()
                        .satisfies(rec -> assertThat(rec.exceptionClass()).contains("ValidationException"))
        );
        dltScenarioService.purgeDeadLetters(validDltTopic);

        String multiDltTopic = "iot/devices/poison-test/DLT";
        dltScenarioService.purgeDeadLetters(multiDltTopic);
        dltScenarioService.triggerMultiPoison(List.of("POISON_E2E_1", "POISON_E2E_2"));
        await().atMost(5, TimeUnit.SECONDS).untilAsserted(() ->
                assertThat(dltScenarioService.peekDeadLetters(multiDltTopic, 5, Duration.ofSeconds(2)))
                        .extracting(MqttDeadLetterRecord::getPayloadAsString)
                        .containsExactly("POISON_E2E_1", "POISON_E2E_2")
        );
        dltScenarioService.purgeDeadLetters(multiDltTopic);

        // 3. MANUAL 确认（ack 与 nackToDlt）与瞬态重试恢复
        governanceScenarioService.clear();
        String manualDltTopic = "iot/governance/manual-ack/DLT";
        dltScenarioService.purgeDeadLetters(manualDltTopic);
        governanceScenarioService.publishManualMessage("E2E_MANUAL_ACCEPT");
        governanceScenarioService.publishManualMessage("REJECT_DLT_E2E");
        governanceScenarioService.publishRetryMessage("TRANSIENT:E2E_JOB");
        await().atMost(5, TimeUnit.SECONDS).untilAsserted(() -> {
            assertThat(governanceScenarioService.getSummary().getManualAckedMessages()).contains("E2E_MANUAL_ACCEPT");
            assertThat(governanceScenarioService.getSummary().getRetriedMessages()).contains("TRANSIENT:E2E_JOB");
            assertThat(dltScenarioService.peekDeadLetters(manualDltTopic, 5, Duration.ofSeconds(2)))
                    .extracting(MqttDeadLetterRecord::getPayloadAsString)
                    .contains("REJECT_DLT_E2E");
        });
        dltScenarioService.purgeDeadLetters(manualDltTopic);

        // 4. 共享过滤器的引用计数流控（pause -> 保持 -> 归零退订 -> resume 恢复消费）
        String sharedA = "mqttContainerGovernanceScenarioService#onSharedFlowPrimary";
        String sharedB = "mqttContainerGovernanceScenarioService#onSharedFlowBackup";
        String sharedFilter = "$share/gov-cluster/iot/governance/shared-flow";
        try {
            governanceScenarioService.pauseEndpoint(sharedA);
            assertThat(governanceScenarioService.getSummary().getSubscribedTopics()).contains(sharedFilter);
            governanceScenarioService.publishSharedFlowMessage("FLOW_MSG_1");
            await().atMost(5, TimeUnit.SECONDS).untilAsserted(() ->
                    assertThat(governanceScenarioService.getSummary().getSharedFlowMessages()).contains("backup:FLOW_MSG_1")
            );

            governanceScenarioService.pauseEndpoint(sharedB);
            assertThat(governanceScenarioService.getSummary().getSubscribedTopics()).doesNotContain(sharedFilter);
        } finally {
            governanceScenarioService.resumeEndpoint(sharedA);
            governanceScenarioService.resumeEndpoint(sharedB);
        }
        assertThat(governanceScenarioService.getSummary().getSubscribedTopics()).contains(sharedFilter);

        // 5. MQTT 5.0 noLocal=true 回环抑制与 retainHandling=2 历史保留不重推验证
        String noLocalEndpoint = "mqttContainerGovernanceScenarioService#onNoLocalMessage";
        String peerClient = "e2e-nolocal-peer";
        clientGovernanceScenarioService.registerClient(
                io.github.atengk.boot.sample.mqtt.dto.RegisterClientRequest.builder()
                        .clientName(peerClient)
                        .clientIdPrefix("e2e-nolocal-peer")
                        .cleanStart(true)
                        .connectionTimeoutSeconds(5)
                        .build()
        );
        try {
            // 暂停后再通过 peer 发布 retained=true 历史保留报文，resume 时因 retainHandling=2 不应重推旧保留报文
            governanceScenarioService.pauseEndpoint(noLocalEndpoint);
            clientGovernanceScenarioService.publishToClient(
                    peerClient,
                    "iot/governance/nolocal-channel",
                    "RETAINED_SUPPRESSED_ON_RESUME",
                    1,
                    true
            );
            Thread.sleep(150);
            governanceScenarioService.resumeEndpoint(noLocalEndpoint);
            Thread.sleep(200);
            assertThat(governanceScenarioService.getNoLocalMessages()).doesNotContain("RETAINED_SUPPRESSED_ON_RESUME");

            governanceScenarioService.publishNoLocalSelfEcho("SELF_ECHO_IGNORED");
            if (broker != null) {
                await().atMost(3, TimeUnit.SECONDS).untilAsserted(() ->
                        assertThat(broker.getReceivedMessages())
                                .anyMatch(m -> "SELF_ECHO_IGNORED".equals(new String(m.getPayload(), java.nio.charset.StandardCharsets.UTF_8)))
                );
            } else {
                Thread.sleep(250);
            }
            assertThat(governanceScenarioService.getNoLocalMessages()).doesNotContain("SELF_ECHO_IGNORED");

            clientGovernanceScenarioService.publishToClient(
                    peerClient,
                    "iot/governance/nolocal-channel",
                    "PEER_DELIVERED_OK",
                    1,
                    false
            );
            await().atMost(5, TimeUnit.SECONDS).untilAsserted(() ->
                    assertThat(governanceScenarioService.getNoLocalMessages()).contains("PEER_DELIVERED_OK")
            );
        } finally {
            governanceScenarioService.resumeEndpoint(noLocalEndpoint);
            governanceScenarioService.clearNoLocalRetained();
            clientGovernanceScenarioService.unregisterClient(peerClient, false);
        }
    }
}
