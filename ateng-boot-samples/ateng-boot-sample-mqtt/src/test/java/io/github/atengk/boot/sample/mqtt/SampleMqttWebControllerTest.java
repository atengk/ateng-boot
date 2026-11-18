package io.github.atengk.boot.sample.mqtt;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.atengk.boot.core.context.TenantContextHolder;
import io.github.atengk.boot.core.context.UserContextHolder;
import io.github.atengk.boot.sample.mqtt.dto.*;
import io.github.atengk.boot.sample.mqtt.service.DeadLetterReplayScenarioService;
import io.github.atengk.boot.sample.mqtt.service.DeviceTelemetryScenarioService;
import io.github.atengk.boot.sample.mqtt.service.MqttContainerGovernanceScenarioService;
import io.github.atengk.boot.sample.mqtt.test.EmbeddedMqttBroker;
import org.junit.jupiter.api.*;
import org.slf4j.MDC;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.asyncDispatch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.request;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * MQTT Sample Web 控制器全场景 MockMvc 集成自验
 * 覆盖 8 大领域 Controller 路由、JSR-303 参数校验拦截、异常映射与 Result<T> 规范契约
 * 包含遥测端到端、同步/异步/声明式 RPC、十六进制编解码、批量与保留消息、死信 DLT 治理、容器运维与动态多客户端治理
 *
 * @author Ateng
 * @since 2026-11-10
 */
@SpringBootTest(classes = SampleMqttApplication.class)
@AutoConfigureMockMvc
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
@DisplayName("MQTT Sample Web 控制器全场景集成测试")
class SampleMqttWebControllerTest {

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
        if (!isLocalProfileActive() && broker != null) {
            registry.add("ateng.boot.mqtt.clients.primary.server-uri", () -> broker.getServerUri());
        }
    }

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private DeviceTelemetryScenarioService telemetryScenarioService;

    @Autowired
    private DeadLetterReplayScenarioService deadLetterScenarioService;

    @Autowired
    private MqttContainerGovernanceScenarioService governanceScenarioService;

    @BeforeEach
    @AfterEach
    void cleanContext() {
        UserContextHolder.clear();
        TenantContextHolder.clear();
        MDC.clear();
    }

    @Test
    @DisplayName("1. 测试 MqttTelemetryController: 正常上报遥测数据并回显监听消费记录")
    void testPublishTelemetrySuccess() throws Exception {
        TelemetryPublishRequest request = TelemetryPublishRequest.builder()
                .deviceId("DEV-WEB-1001")
                .temp(28.4)
                .humidity(62.5)
                .qos(1)
                .headers(Map.of("x-custom-tag", "production-edge"))
                .build();

        mockMvc.perform(post("/sample/mqtt/telemetry/publish")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.deviceId").value("DEV-WEB-1001"))
                .andExpect(jsonPath("$.data.topic").value("iot/devices/DEV-WEB-1001/telemetry"))
                .andExpect(jsonPath("$.data.telemetry.temperature").value(28.4))
                .andExpect(jsonPath("$.data.telemetry.humidity").value(62.5));
    }

    @Test
    @DisplayName("1-1. 测试 MqttTelemetryController: 显式指定 QoS=2 上报遥测报文并验证透传")
    void testPublishTelemetryWithExplicitQos() throws Exception {
        TelemetryPublishRequest request = TelemetryPublishRequest.builder()
                .deviceId("DEV-WEB-QOS2")
                .temp(22.3)
                .humidity(45.6)
                .qos(2)
                .build();

        mockMvc.perform(post("/sample/mqtt/telemetry/publish")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.deviceId").value("DEV-WEB-QOS2"))
                .andExpect(jsonPath("$.data.telemetry.temperature").value(22.3))
                .andExpect(jsonPath("$.data.telemetry.humidity").value(45.6));
    }

    @Test
    @DisplayName("2. 测试 MqttTelemetryController: JSR-303 参数校验拦截非法入参 (Fail-Fast)")
    void testPublishTelemetryValidationFailed() throws Exception {
        TelemetryPublishRequest invalidRequest = TelemetryPublishRequest.builder()
                .deviceId("") // 空 deviceId
                .temp(null)   // 缺少温度
                .humidity(null) // 缺少湿度
                .qos(5)       // 超出 QoS 范围 [0, 2]
                .build();

        mockMvc.perform(post("/sample/mqtt/telemetry/publish")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(invalidRequest)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(not(200)))
                .andExpect(jsonPath("$.success").value(false));
    }

    @Test
    @DisplayName("3. 测试 MqttTelemetryController: 查询指定设备最新遥测记录")
    void testGetLatestTelemetry() throws Exception {
        mockMvc.perform(get("/sample/mqtt/telemetry/latest")
                        .param("deviceId", "DEV-WEB-1001"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.success").value(true));
    }

    @Test
    @DisplayName("4. 测试 MqttTelemetryController: 投递集群共享度量数据")
    void testPublishClusterMetric() throws Exception {
        mockMvc.perform(post("/sample/mqtt/telemetry/cluster-metric")
                        .param("metric", "{\"cpu\": 58.2, \"mem\": 74.1}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data").value(containsString("集群共享度量已投递")));
    }

    @Test
    @DisplayName("5. 测试 MqttRpcController: 同步阻塞 RPC 下发指令并接收设备应答回执")
    void testRpcControlSyncSuccess() throws Exception {
        DeviceCommandRequest request = DeviceCommandRequest.builder()
                .action("START_MOTOR")
                .speed(1500)
                .timeoutSeconds(5)
                .build();

        mockMvc.perform(post("/sample/mqtt/rpc/control-sync")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.success").value(true))
                .andExpect(jsonPath("$.data.message").value("ACTION_EXECUTED_SUCCESS"));
    }

    @Test
    @DisplayName("6. 测试 MqttRpcController: 异步非阻塞 RPC 下发指令并通过 CompletableFuture 应答")
    void testRpcControlAsyncSuccess() throws Exception {
        DeviceCommandRequest request = DeviceCommandRequest.builder()
                .action("ADJUST_VALVE")
                .speed(2200)
                .timeoutSeconds(5)
                .build();

        MvcResult mvcResult = mockMvc.perform(post("/sample/mqtt/rpc/control-async")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(request().asyncStarted())
                .andReturn();

        mockMvc.perform(asyncDispatch(mvcResult))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.success").value(true))
                .andExpect(jsonPath("$.data.message").value("ACTION_EXECUTED_SUCCESS"));
    }

    @Test
    @DisplayName("7. 测试 MqttRpcController: JSR-303 参数校验拦截非法 RPC 入参 (Fail-Fast)")
    void testRpcControlValidationFailed() throws Exception {
        DeviceCommandRequest invalidRequest = DeviceCommandRequest.builder()
                .action("")   // 空 action
                .speed(-10)   // 负数转速
                .timeoutSeconds(100) // 超过最大限制 60s
                .build();

        mockMvc.perform(post("/sample/mqtt/rpc/control-sync")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(invalidRequest)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(not(200)))
                .andExpect(jsonPath("$.success").value(false));
    }

    @Test
    @DisplayName("7-1. 测试 MqttRpcController: 同步阻塞 RPC 下发超时降级返回 503")
    void testRpcControlSyncTimeout() throws Exception {
        DeviceCommandRequest timeoutRequest = DeviceCommandRequest.builder()
                .action("SIMULATE_TIMEOUT_SYNC")
                .speed(1200)
                .timeoutSeconds(1)
                .build();

        mockMvc.perform(post("/sample/mqtt/rpc/control-sync")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(timeoutRequest)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(503))
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.msg").value(containsString("设备指令响应超时")));
    }

    @Test
    @DisplayName("7-2. 测试 MqttRpcController: 异步非阻塞 RPC 下发超时降级返回 503")
    void testRpcControlAsyncTimeout() throws Exception {
        DeviceCommandRequest timeoutRequest = DeviceCommandRequest.builder()
                .action("SIMULATE_TIMEOUT_ASYNC")
                .speed(1800)
                .timeoutSeconds(1)
                .build();

        MvcResult mvcResult = mockMvc.perform(post("/sample/mqtt/rpc/control-async")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(timeoutRequest)))
                .andExpect(request().asyncStarted())
                .andReturn();

        mockMvc.perform(asyncDispatch(mvcResult))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(503))
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.msg").value(containsString("设备指令响应超时")));
    }

    @Test
    @DisplayName("8. 测试 MqttIndustrialHexController: 正常下发国标 Hex 报文并验证 HexPayload 转译")
    void testIndustrialHexPublishSuccess() throws Exception {
        HexPublishRequest request = HexPublishRequest.builder()
                .deviceId("meter-hex-01")
                .hexData("68AAAAAAAAAAAA6811043333333316")
                .build();

        mockMvc.perform(post("/sample/mqtt/hex/publish")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.deviceId").value("meter-hex-01"))
                .andExpect(jsonPath("$.data.originalHex").value("68AAAAAAAAAAAA6811043333333316"))
                .andExpect(jsonPath("$.data.formattedHex").value("68AAAAAAAAAAAA6811043333333316"))
                .andExpect(jsonPath("$.data.byteLength").value(15));
    }

    @Test
    @DisplayName("9. 测试 MqttIndustrialHexController: 非法 Hex 字符正则校验拦截")
    void testIndustrialHexValidationFailed() throws Exception {
        HexPublishRequest invalidRequest = HexPublishRequest.builder()
                .deviceId("meter-hex-01")
                .hexData("INVALID_HEX_GHIJK") // 非法十六进制字符
                .build();

        mockMvc.perform(post("/sample/mqtt/hex/publish")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(invalidRequest)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(not(200)))
                .andExpect(jsonPath("$.success").value(false));
    }

    @Test
    @DisplayName("10. 测试 MqttBatchAndRetainedController: 批量原子并发下发报文")
    void testBatchPublishSuccess() throws Exception {
        BatchPublishRequest request = BatchPublishRequest.builder()
                .records(List.of(
                        BatchPublishItemRequest.builder()
                                .topic("iot/devices/batch-01/batch")
                                .payload("BATCH_ITEM_1")
                                .qos(1)
                                .build(),
                        BatchPublishItemRequest.builder()
                                .topic("iot/devices/batch-02/batch")
                                .payload("BATCH_ITEM_2")
                                .qos(1)
                                .build()
                ))
                .build();

        mockMvc.perform(post("/sample/mqtt/batch/publish")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data").value(containsString("成功原子批量投递 2 条报文")));
    }

    @Test
    @DisplayName("11. 测试 MqttBatchAndRetainedController: 发布设备影子保留消息与对称清理闭环")
    void testRetainedStatusLifecycle() throws Exception {
        RetainedStatusRequest request = RetainedStatusRequest.builder()
                .deviceId("dev-shadow-01")
                .status("ONLINE")
                .build();

        // 1. 发布保留消息
        mockMvc.perform(post("/sample/mqtt/batch/retained-status")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data").value(containsString("ONLINE")));

        // 2. 对称清除保留消息
        mockMvc.perform(delete("/sample/mqtt/batch/clear-retained")
                        .param("deviceId", "dev-shadow-01"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data").value(containsString("成功对称清空设备保留状态消息")));
    }

    @Test
    @DisplayName("11-1. 测试 MqttBatchAndRetainedController: 参数校验拦截空记录与空白 deviceId (Fail-Fast)")
    void testBatchAndRetainedValidationFailed() throws Exception {
        // 1. 空批量记录
        mockMvc.perform(post("/sample/mqtt/batch/publish")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"records\":[]}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(not(200)))
                .andExpect(jsonPath("$.success").value(false));

        // 2. 空白 deviceId 清空保留消息
        mockMvc.perform(delete("/sample/mqtt/batch/clear-retained")
                        .param("deviceId", "   "))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(not(200)))
                .andExpect(jsonPath("$.success").value(false));

        // 3. 非法 RetainedStatusRequest
        RetainedStatusRequest invalid = RetainedStatusRequest.builder().deviceId("").status("").build();
        mockMvc.perform(post("/sample/mqtt/batch/retained-status")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(invalid)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(not(200)))
                .andExpect(jsonPath("$.success").value(false));
    }

    @Test
    @DisplayName("12. 测试 MqttDeadLetterController: 毒丸触发、死信只读窥探、受控重放与清空闭环")
    void testDeadLetterLifecycle() throws Exception {
        // 1. 触发毒丸消息
        mockMvc.perform(post("/sample/mqtt/dead-letter/trigger-poison")
                        .param("payload", "POISON_PAYLOAD"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.success").value(true));

        // 2. 离线只读窥探死信
        mockMvc.perform(get("/sample/mqtt/dead-letter/peek")
                        .param("topic", "iot/devices/poison-test/DLT")
                        .param("count", "5")
                        .param("timeoutSeconds", "2"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data[0].dltTopic").value("iot/devices/poison-test/DLT"))
                .andExpect(jsonPath("$.data[0].payloadAsString").value("POISON_PAYLOAD"))
                .andExpect(jsonPath("$.data[0].exceptionMessage").value("FATAL_POISON_PILL"));

        // 3. 受控重放死信至恢复主题
        DeadLetterReplayRequest replayRequest = DeadLetterReplayRequest.builder()
                .sourceTopic("iot/devices/poison-test/DLT")
                .targetTopic("iot/devices/poison-recovered")
                .count(5)
                .timeoutSeconds(2)
                .build();

        mockMvc.perform(post("/sample/mqtt/dead-letter/replay")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(replayRequest)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data").value(containsString("成功受控重放")));

        await().atMost(5, TimeUnit.SECONDS).untilAsserted(() ->
                assertThat(deadLetterScenarioService.getRecoveredMessages()).contains("POISON_PAYLOAD")
        );

        // 4. 清空死信队列
        mockMvc.perform(delete("/sample/mqtt/dead-letter/purge")
                        .param("topic", "iot/devices/poison-test/DLT"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data").value(containsString("已成功彻底清空死信主题")));
    }

    @Test
    @DisplayName("13. 测试 MqttDeadLetterController: JSR-303 参数校验拦截非法重放入参")
    void testDeadLetterValidationFailed() throws Exception {
        DeadLetterReplayRequest invalidRequest = DeadLetterReplayRequest.builder()
                .sourceTopic("") // 空源主题
                .targetTopic("") // 空目标主题
                .count(-1)       // 非法数量
                .build();

        mockMvc.perform(post("/sample/mqtt/dead-letter/replay")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(invalidRequest)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(not(200)))
                .andExpect(jsonPath("$.success").value(false));
    }

    @Test
    @DisplayName("14. 测试 MqttContainerController: 端点状态探查、动态暂停与恢复运维闭环")
    void testContainerEndpointsLifecycle() throws Exception {
        // 1. 查询全部端点状态
        mockMvc.perform(get("/sample/mqtt/container/endpoints"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data").isArray());

        String targetEndpoint = "mqttContainerGovernanceScenarioService#onGovernanceMessage";

        // 2. 暂停指定端点
        mockMvc.perform(post("/sample/mqtt/container/pause")
                        .param("endpointId", targetEndpoint))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data").value(containsString("已成功暂停")));
        assertThat(governanceScenarioService.isPaused(targetEndpoint)).isTrue();

        // 3. 投递测试消息并验证暂停期间未被消费分发
        governanceScenarioService.clear();
        mockMvc.perform(post("/sample/mqtt/container/publish-test")
                        .param("message", "MESSAGE_DURING_PAUSE"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.success").value(true));
        TimeUnit.MILLISECONDS.sleep(300);
        assertThat(governanceScenarioService.getReceivedMessages()).doesNotContain("MESSAGE_DURING_PAUSE");

        // 4. 恢复指定端点并验证消息分发恢复
        mockMvc.perform(post("/sample/mqtt/container/resume")
                        .param("endpointId", targetEndpoint))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data").value(containsString("已成功恢复")));
        assertThat(governanceScenarioService.isPaused(targetEndpoint)).isFalse();

        mockMvc.perform(post("/sample/mqtt/container/publish-test")
                        .param("message", "MESSAGE_AFTER_RESUME"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200));
        await().atMost(5, TimeUnit.SECONDS).untilAsserted(() ->
                assertThat(governanceScenarioService.getReceivedMessages()).contains("MESSAGE_AFTER_RESUME")
        );
    }

    @Test
    @DisplayName("14-1. 测试 MqttContainerController: 参数校验拦截空白 endpointId (Fail-Fast)")
    void testContainerValidationFailed() throws Exception {
        // 1. 空白 endpointId 暂停
        mockMvc.perform(post("/sample/mqtt/container/pause")
                        .param("endpointId", "   "))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(not(200)))
                .andExpect(jsonPath("$.success").value(false));

        // 2. 空白 endpointId 恢复
        mockMvc.perform(post("/sample/mqtt/container/resume")
                        .param("endpointId", ""))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(not(200)))
                .andExpect(jsonPath("$.success").value(false));
    }

    @Test
    @DisplayName("15. 测试 MqttTransactionController: 本地事务提交后触发 sendAfterCommit 发信")
    void testTxTelemetryCommitSuccess() throws Exception {
        TxTelemetryRequest request = TxTelemetryRequest.builder()
                .deviceId("DEV-TX-1001")
                .temp(26.8)
                .humidity(55.2)
                .shouldRollback(false)
                .build();

        mockMvc.perform(post("/sample/mqtt/transaction/record-telemetry")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data").value(containsString("事务已成功提交并触发 sendAfterCommit 投递")));
    }

    @Test
    @DisplayName("16. 测试 MqttTransactionController: 本地事务回滚安全取消发信钩子防脏读")
    void testTxTelemetryRollbackCancelled() throws Exception {
        TxTelemetryRequest request = TxTelemetryRequest.builder()
                .deviceId("DEV-TX-1002")
                .temp(31.5)
                .humidity(68.0)
                .shouldRollback(true)
                .build();

        mockMvc.perform(post("/sample/mqtt/transaction/record-telemetry")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data").value(containsString("模拟事务回滚，发信钩子已安全取消，下游零脏读")));
    }

    @Test
    @DisplayName("17. 测试 MqttTransactionController: JSR-303 参数校验拦截非法入参")
    void testTxTelemetryValidationFailed() throws Exception {
        TxTelemetryRequest invalidRequest = TxTelemetryRequest.builder()
                .deviceId("")
                .temp(null)
                .humidity(50.0)
                .shouldRollback(false)
                .build();

        mockMvc.perform(post("/sample/mqtt/transaction/record-telemetry")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(invalidRequest)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(not(200)))
                .andExpect(jsonPath("$.success").value(false));
    }

    @Test
    @DisplayName("18. 测试 MqttClientGovernanceController: 动态注册客户端 -> use() 定向收发 -> 快照查询 -> 注销与 JSR-303 校验")
    void testClientGovernanceLifecycleAndValidation() throws Exception {
        String dynamicClient = "park-north";
        String dynamicTopic = "iot/clients/" + dynamicClient + "/command";

        // 1. JSR-303 拦截空白客户端名与非法 QoS
        RegisterClientRequest invalidRegister = RegisterClientRequest.builder()
                .clientName("")
                .build();
        mockMvc.perform(post("/sample/mqtt/clients/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(invalidRegister)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(not(200)))
                .andExpect(jsonPath("$.success").value(false));

        ClientPublishRequest invalidQosPublish = ClientPublishRequest.builder()
                .clientName(dynamicClient)
                .topic(dynamicTopic)
                .payload("INVALID_QOS")
                .qos(5)
                .build();
        mockMvc.perform(post("/sample/mqtt/clients/publish")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(invalidQosPublish)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(not(200)))
                .andExpect(jsonPath("$.success").value(false));

        // 禁止注销默认主客户端 & 禁止向未注册客户端发布
        mockMvc.perform(delete("/sample/mqtt/clients/primary"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(not(200)))
                .andExpect(jsonPath("$.success").value(false));
        ClientPublishRequest unknownClientPublish = ClientPublishRequest.builder()
                .clientName("unknown-client")
                .topic(dynamicTopic)
                .payload("FAIL")
                .qos(1)
                .build();
        mockMvc.perform(post("/sample/mqtt/clients/publish")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(unknownClientPublish)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(not(200)))
                .andExpect(jsonPath("$.success").value(false));

        // 2. 动态注册命名客户端（缺省复用主客户端 Broker 地址与认证基线）
        RegisterClientRequest registerRequest = RegisterClientRequest.builder()
                .clientName(dynamicClient)
                .clientIdPrefix("sample-park-north")
                .cleanStart(true)
                .connectionTimeoutSeconds(5)
                .build();
        mockMvc.perform(post("/sample/mqtt/clients/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(registerRequest)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.clientName").value(dynamicClient))
                .andExpect(jsonPath("$.data.connected").value(true));

        try {
            // 2-1. 重复注册同名动态客户端 Fail-Fast 拦截
            mockMvc.perform(post("/sample/mqtt/clients/register")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(registerRequest)))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.code").value(not(200)))
                    .andExpect(jsonPath("$.success").value(false));
            // 3. 查询受管客户端列表与健康快照
            mockMvc.perform(get("/sample/mqtt/clients"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.code").value(200))
                    .andExpect(jsonPath("$.success").value(true))
                    .andExpect(jsonPath("$.data[?(@.clientName == '" + dynamicClient + "')].connected")
                            .value(org.hamcrest.Matchers.contains(true)));

            // 4. 通过 MqttTemplate.use(clientName) 定向投递并消费回显
            ClientPublishRequest publishRequest = ClientPublishRequest.builder()
                    .clientName(dynamicClient)
                    .topic(dynamicTopic)
                    .payload("OPEN_BARRIER_GATE_1")
                    .qos(1)
                    .retained(false)
                    .build();
            mockMvc.perform(post("/sample/mqtt/clients/publish")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(publishRequest)))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.code").value(200))
                    .andExpect(jsonPath("$.success").value(true));

            await().atMost(5, TimeUnit.SECONDS).untilAsserted(() ->
                    mockMvc.perform(get("/sample/mqtt/clients/consumed")
                                    .param("clientName", dynamicClient))
                            .andExpect(status().isOk())
                            .andExpect(jsonPath("$.data[0]").value("OPEN_BARRIER_GATE_1"))
            );
        } finally {
            // 5. 安全注销动态客户端并确认已从快照清单移除
            mockMvc.perform(delete("/sample/mqtt/clients/" + dynamicClient))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.code").value(200))
                    .andExpect(jsonPath("$.success").value(true));
        }

        mockMvc.perform(get("/sample/mqtt/clients"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[?(@.clientName == '" + dynamicClient + "')]").isEmpty());
    }

    @Test
    @DisplayName("19. 测试 MqttRpcController: @MqttReply 声明式同步与 CompletableFuture 异步应答")
    void testRpcDeclarativeReplySyncAndAsync() throws Exception {
        DeviceCommandRequest syncRequest = DeviceCommandRequest.builder()
                .action("SYNC_STATUS")
                .speed(1600)
                .timeoutSeconds(5)
                .build();

        mockMvc.perform(post("/sample/mqtt/rpc/declarative-reply")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(syncRequest)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.success").value(true))
                .andExpect(jsonPath("$.data.message").value("DECLARATIVE_SYNC_OK"));

        DeviceCommandRequest asyncRequest = DeviceCommandRequest.builder()
                .action("ASYNC_CALIBRATE")
                .speed(1800)
                .timeoutSeconds(5)
                .build();

        MvcResult asyncResult = mockMvc.perform(post("/sample/mqtt/rpc/declarative-reply-async")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(asyncRequest)))
                .andExpect(request().asyncStarted())
                .andReturn();

        mockMvc.perform(asyncDispatch(asyncResult))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.success").value(true))
                .andExpect(jsonPath("$.data.message").value("DECLARATIVE_ASYNC_OK"));
    }

    @Test
    @DisplayName("20. 测试 MqttDeadLetterController: @Valid 消费校验直入死信与连续多条毒丸有序 peek/replay/purge")
    void testDeadLetterValidFailAndOrderedMultiPoisonStore() throws Exception {
        // 1. JSR-303 网关来源必填校验拦截（第一道防线）
        WorkOrderDispatchRequest invalidGatewayReq = WorkOrderDispatchRequest.builder()
                .gatewayId("")
                .orderNo("WO-01")
                .targetTemp(100)
                .build();
        mockMvc.perform(post("/sample/mqtt/dead-letter/trigger-valid-fail")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(invalidGatewayReq)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(not(200)))
                .andExpect(jsonPath("$.success").value(false));

        // 2. @Valid 消费侧模型校验失败：0 次重试直接转入 iot/devices/valid-order/DLT
        String validDltTopic = "iot/devices/valid-order/DLT";
        mockMvc.perform(delete("/sample/mqtt/dead-letter/purge").param("topic", validDltTopic))
                .andExpect(status().isOk());

        WorkOrderDispatchRequest invalidOrderPayload = WorkOrderDispatchRequest.builder()
                .gatewayId("gw-south-01")
                .orderNo("")
                .targetTemp(-5)
                .build();
        mockMvc.perform(post("/sample/mqtt/dead-letter/trigger-valid-fail")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(invalidOrderPayload)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200));

        await().atMost(5, TimeUnit.SECONDS).untilAsserted(() ->
                mockMvc.perform(get("/sample/mqtt/dead-letter/peek")
                                .param("topic", validDltTopic)
                                .param("count", "5"))
                        .andExpect(status().isOk())
                        .andExpect(jsonPath("$.data.length()").value(1))
                        .andExpect(jsonPath("$.data[0].exceptionClass").value(containsString("ValidationException")))
        );
        mockMvc.perform(delete("/sample/mqtt/dead-letter/purge").param("topic", validDltTopic))
                .andExpect(status().isOk());

        // 3. 连续投递 3 条毒丸：依赖 MqttDeadLetterStore 全量有序留存不覆盖 -> 局部重放 2 条 -> 剩余 1 条 -> purge 清空
        String multiDltTopic = "iot/devices/poison-test/DLT";
        mockMvc.perform(delete("/sample/mqtt/dead-letter/purge").param("topic", multiDltTopic))
                .andExpect(status().isOk());
        deadLetterScenarioService.clear();

        MultiPoisonRequest invalidElementReq = MultiPoisonRequest.builder()
                .payloads(List.of("   "))
                .build();
        mockMvc.perform(post("/sample/mqtt/dead-letter/trigger-multi-poison")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(invalidElementReq)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(not(200)))
                .andExpect(jsonPath("$.success").value(false));

        MultiPoisonRequest multiRequest = MultiPoisonRequest.builder()
                .payloads(List.of("POISON_SEQ_1", "POISON_SEQ_2", "POISON_SEQ_3"))
                .build();
        mockMvc.perform(post("/sample/mqtt/dead-letter/trigger-multi-poison")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(multiRequest)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200));

        await().atMost(5, TimeUnit.SECONDS).untilAsserted(() ->
                mockMvc.perform(get("/sample/mqtt/dead-letter/peek")
                                .param("topic", multiDltTopic)
                                .param("count", "10"))
                        .andExpect(status().isOk())
                        .andExpect(jsonPath("$.data.length()").value(3))
                        .andExpect(jsonPath("$.data[0].payloadAsString").value("POISON_SEQ_1"))
                        .andExpect(jsonPath("$.data[1].payloadAsString").value("POISON_SEQ_2"))
                        .andExpect(jsonPath("$.data[2].payloadAsString").value("POISON_SEQ_3"))
        );

        DeadLetterReplayRequest partialReplay = DeadLetterReplayRequest.builder()
                .sourceTopic(multiDltTopic)
                .targetTopic("iot/devices/poison-recovered")
                .count(2)
                .timeoutSeconds(2)
                .build();
        mockMvc.perform(post("/sample/mqtt/dead-letter/replay")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(partialReplay)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data").value(containsString("成功受控重放 2 条")));

        await().atMost(5, TimeUnit.SECONDS).untilAsserted(() ->
                assertThat(deadLetterScenarioService.getRecoveredMessages())
                        .contains("POISON_SEQ_1", "POISON_SEQ_2")
        );

        mockMvc.perform(get("/sample/mqtt/dead-letter/peek")
                        .param("topic", multiDltTopic)
                        .param("count", "10"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.length()").value(1))
                .andExpect(jsonPath("$.data[0].payloadAsString").value("POISON_SEQ_3"));

        mockMvc.perform(delete("/sample/mqtt/dead-letter/purge").param("topic", multiDltTopic))
                .andExpect(status().isOk());
    }

    @Test
    @DisplayName("21. 测试 MqttContainerController: MANUAL 手动确认/拒绝入死信、瞬态重试与引用计数流控运维")
    void testContainerManualAckRetryAndRefCountFlowControl() throws Exception {
        governanceScenarioService.clear();
        String manualDltTopic = "iot/governance/manual-ack/DLT";
        mockMvc.perform(delete("/sample/mqtt/dead-letter/purge").param("topic", manualDltTopic))
                .andExpect(status().isOk());

        // 0. JSR-303 参数校验拦截空白 payload
        mockMvc.perform(post("/sample/mqtt/container/publish-manual").param("payload", "  "))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(not(200)))
                .andExpect(jsonPath("$.success").value(false));
        mockMvc.perform(post("/sample/mqtt/container/publish-retry").param("payload", ""))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(not(200)))
                .andExpect(jsonPath("$.success").value(false));

        // 1. MANUAL 模式：正常确认与显式 nackToDlt
        mockMvc.perform(post("/sample/mqtt/container/publish-manual").param("payload", "MANUAL_OK_1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200));
        mockMvc.perform(post("/sample/mqtt/container/publish-manual").param("payload", "REJECT_DLT_ITEM"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200));

        await().atMost(5, TimeUnit.SECONDS).untilAsserted(() ->
                mockMvc.perform(get("/sample/mqtt/container/summary"))
                        .andExpect(status().isOk())
                        .andExpect(jsonPath("$.data.manualAckedMessages[0]").value("MANUAL_OK_1"))
        );
        await().atMost(5, TimeUnit.SECONDS).untilAsserted(() ->
                mockMvc.perform(get("/sample/mqtt/dead-letter/peek")
                                .param("topic", manualDltTopic)
                                .param("count", "5"))
                        .andExpect(status().isOk())
                        .andExpect(jsonPath("$.data.length()").value(1))
                        .andExpect(jsonPath("$.data[0].payloadAsString").value("REJECT_DLT_ITEM"))
        );
        mockMvc.perform(delete("/sample/mqtt/dead-letter/purge").param("topic", manualDltTopic))
                .andExpect(status().isOk());

        // 2. 可恢复瞬态异常重试恢复
        mockMvc.perform(post("/sample/mqtt/container/publish-retry").param("payload", "TRANSIENT:JOB-77"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200));
        await().atMost(5, TimeUnit.SECONDS).untilAsserted(() ->
                mockMvc.perform(get("/sample/mqtt/container/summary"))
                        .andExpect(status().isOk())
                        .andExpect(jsonPath("$.data.retriedMessages[0]").value("TRANSIENT:JOB-77"))
                        .andExpect(jsonPath("$.data.retryAttemptTraces.length()").value(2))
                        .andExpect(jsonPath("$.data.retryAttemptTraces[0]").value("attempt-1:TRANSIENT:JOB-77"))
                        .andExpect(jsonPath("$.data.retryAttemptTraces[1]").value("attempt-2:TRANSIENT:JOB-77"))
        );

        // 3. 引用计数真实 Broker 流控：同一共享过滤器两个端点，只暂停一个保留订阅，全暂停退订，恢复重建订阅
        String sharedA = "mqttContainerGovernanceScenarioService#onSharedFlowPrimary";
        String sharedB = "mqttContainerGovernanceScenarioService#onSharedFlowBackup";
        String sharedFilter = "$share/gov-cluster/iot/governance/shared-flow";

        try {
            mockMvc.perform(post("/sample/mqtt/container/pause").param("endpointId", sharedA))
                    .andExpect(status().isOk());
            mockMvc.perform(get("/sample/mqtt/container/summary"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.subscribedTopics", org.hamcrest.Matchers.hasItem(sharedFilter)));

            governanceScenarioService.publishSharedFlowMessage("FLOW_MSG_B");
            await().atMost(5, TimeUnit.SECONDS).untilAsserted(() ->
                    mockMvc.perform(get("/sample/mqtt/container/summary"))
                            .andExpect(status().isOk())
                            .andExpect(jsonPath("$.data.sharedFlowMessages", org.hamcrest.Matchers.hasItem("backup:FLOW_MSG_B")))
            );

            mockMvc.perform(post("/sample/mqtt/container/pause").param("endpointId", sharedB))
                    .andExpect(status().isOk());
            mockMvc.perform(get("/sample/mqtt/container/summary"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.subscribedTopics", not(org.hamcrest.Matchers.hasItem(sharedFilter))));
        } finally {
            mockMvc.perform(post("/sample/mqtt/container/resume").param("endpointId", sharedA))
                    .andExpect(status().isOk());
            mockMvc.perform(post("/sample/mqtt/container/resume").param("endpointId", sharedB))
                    .andExpect(status().isOk());
        }
        mockMvc.perform(get("/sample/mqtt/container/summary"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.subscribedTopics", org.hamcrest.Matchers.hasItem(sharedFilter)));
    }
}
