package io.github.atengk.boot.sample.mqtt;

import io.github.atengk.boot.mqtt.codec.HexPayload;
import io.github.atengk.boot.mqtt.template.MqttTemplate;
import io.github.atengk.boot.sample.mqtt.listener.DeviceTelemetryListener;
import io.github.atengk.boot.sample.mqtt.model.CommandResponse;
import io.github.atengk.boot.sample.mqtt.model.DeviceTelemetry;
import io.github.atengk.boot.sample.mqtt.service.MqttTransactionalOrderService;
import io.github.atengk.boot.sample.mqtt.test.EmbeddedMqttBroker;
import io.micrometer.core.instrument.MeterRegistry;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;

import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * ateng-boot-sample-mqtt 端到端自动化集成自验用例
 * 依托纯内存 Netty MQTT Broker 测试桩，100% 离线秒级全绿验证全特性
 *
 * @author Ateng
 * @since 2026-10-23
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureMockMvc
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
    private MockMvc mockMvc;

    @Autowired
    private MqttTemplate mqttTemplate;

    @Autowired
    private DeviceTelemetryListener telemetryListener;

    @Autowired
    private MqttTransactionalOrderService transactionalService;

    @Autowired
    private MeterRegistry meterRegistry;

    @BeforeEach
    void setUp() {
        telemetryListener.clear();
    }

    @Test
    @DisplayName("端到端测试 1：设备遥测上行发布与 @MqttListener 声明式消费（通配符变量与上下文注入）")
    void testTelemetryPublishAndListener() throws Exception {
        // 1. 通过 HTTP 控制器发布遥测数据
        mockMvc.perform(post("/api/mqtt/publish/telemetry")
                        .param("deviceId", "sensor-001")
                        .param("temp", "26.8")
                        .param("humidity", "55.4"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200));

        // 2. 异步等待消费者线程调度完成
        await().atMost(5, TimeUnit.SECONDS).untilAsserted(() -> {
            assertThat(telemetryListener.getReceivedTelemetries()).hasSize(1);
            DeviceTelemetry telemetry = telemetryListener.getReceivedTelemetries().get(0);
            assertThat(telemetry.getDeviceId()).isEqualTo("sensor-001");
            assertThat(telemetry.getTemperature()).isEqualTo(26.8);
            assertThat(telemetry.getHumidity()).isEqualTo(55.4);
            assertThat(telemetryListener.getLastTraceId()).isNotBlank();
        });

        // 3. 通过 GET 接口查询最新遥测数据
        mockMvc.perform(get("/api/mqtt/telemetries"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data[0].deviceId").value("sensor-001"));
    }

    @Test
    @DisplayName("端到端测试 2：国标十六进制报文（HexPayload）双向转译发布与消费")
    void testHexPayloadPublishAndConsume() throws Exception {
        String hexString = "68AAAAAAAAAAAA6811043333333316";

        mockMvc.perform(post("/api/mqtt/publish/hex")
                        .param("deviceId", "meter-88")
                        .param("hexString", hexString))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200));

        await().atMost(5, TimeUnit.SECONDS).untilAsserted(() -> {
            assertThat(telemetryListener.getReceivedHexPayloads()).hasSize(1);
            HexPayload payload = telemetryListener.getReceivedHexPayloads().get(0);
            assertThat(payload.getHexString().toUpperCase()).isEqualTo(hexString.toUpperCase());
            assertThat(telemetryListener.getReceivedHexDeviceIds()).contains("meter-88");
        });
    }

    @Test
    @DisplayName("端到端测试 3：$share 共享订阅集群均衡消费")
    void testSharedSubscription() throws Exception {
        String metricData = "{\"cpu\":42.5,\"mem\":68.0}";

        mockMvc.perform(post("/api/mqtt/publish/cluster-metric")
                        .param("metric", metricData))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200));

        await().atMost(5, TimeUnit.SECONDS).untilAsserted(() -> {
            assertThat(telemetryListener.getReceivedClusterMetrics()).contains(metricData);
        });
    }

    @Test
    @DisplayName("端到端测试 4：MQTT 5.0 同步 RPC 请求-响应（sendAndReceive）与下行控制闭环")
    void testSyncRpcRequestReply() throws Exception {
        mockMvc.perform(post("/api/mqtt/rpc/control")
                        .param("action", "RESET_CALIBRATION")
                        .param("speed", "200"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data.success").value(true))
                .andExpect(jsonPath("$.data.message").value("ACTION_EXECUTED_SUCCESS"));
    }

    @Test
    @DisplayName("端到端测试 5：本地事务安全协同发信（sendAfterCommit）")
    void testTransactionalSendAfterCommit() throws Exception {
        mockMvc.perform(post("/api/mqtt/tx/telemetry")
                        .param("deviceId", "tx-device-01")
                        .param("temp", "31.2")
                        .param("humidity", "48.0"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200));

        assertThat(transactionalService.getDatabaseRecordsCount()).isGreaterThanOrEqualTo(1);

        await().atMost(5, TimeUnit.SECONDS).untilAsserted(() -> {
            assertThat(telemetryListener.getReceivedTelemetries())
                    .anyMatch(t -> "tx-device-01".equals(t.getDeviceId()));
        });
    }

    @Test
    @DisplayName("端到端测试 6：Actuator /actuator/health 探针检查与连接就绪")
    void testActuatorHealthIndicator() throws Exception {
        mockMvc.perform(get("/actuator/health"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("UP"))
                .andExpect(jsonPath("$.components.mqtt.status").value("UP"))
                .andExpect(jsonPath("$.components.mqtt.details.clients.primary.status").value("CONNECTED"));
    }

    @Test
    @DisplayName("端到端测试 7：Micrometer 云原生黄金指标抓取验证")
    void testMicrometerMetrics() {
        // 1. 验证客户端连接 Gauge
        assertThat(meterRegistry.find("mqtt.client.status")
                .tag("client", "primary")
                .gauge())
                .isNotNull()
                .satisfies(gauge -> assertThat(gauge.value()).isEqualTo(1.0));

        // 2. 验证发信 Counter 与耗时 Timer
        assertThat(meterRegistry.find("mqtt.message.published").counters()).isNotEmpty();
        assertThat(meterRegistry.find("mqtt.publish.latency").timers()).isNotEmpty();

        // 3. 验证收信 Counter
        assertThat(meterRegistry.find("mqtt.message.received").counters()).isNotEmpty();
    }
}
