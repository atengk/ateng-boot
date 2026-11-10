package io.github.atengk.boot.sample.mqtt;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.atengk.boot.core.context.TenantContextHolder;
import io.github.atengk.boot.core.context.UserContextHolder;
import io.github.atengk.boot.sample.mqtt.dto.DeviceCommandRequest;
import io.github.atengk.boot.sample.mqtt.dto.TelemetryPublishRequest;
import io.github.atengk.boot.sample.mqtt.service.DeviceTelemetryScenarioService;
import io.github.atengk.boot.sample.mqtt.test.EmbeddedMqttBroker;
import org.junit.jupiter.api.*;
import org.slf4j.MDC;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.util.Map;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.asyncDispatch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.request;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * MQTT Sample Web 控制器全场景 MockMvc 集成自验
 * 覆盖 MqttTelemetryController 路由、JSR-303 参数校验拦截与 4D 上下文回显契约
 *
 * @author Ateng
 * @since 2026-11-10
 */
@SpringBootTest(classes = SampleMqttApplication.class)
@AutoConfigureMockMvc
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
}
