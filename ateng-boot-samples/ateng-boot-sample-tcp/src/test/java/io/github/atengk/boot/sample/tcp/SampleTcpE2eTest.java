package io.github.atengk.boot.sample.tcp;

import io.github.atengk.boot.core.util.JsonUtil;
import io.github.atengk.boot.sample.tcp.model.*;
import io.github.atengk.boot.sample.tcp.service.VehicleControlService;
import io.github.atengk.boot.sample.tcp.simulator.VehicleSimulator;
import io.github.atengk.boot.tcp.actuator.TcpActuatorEndpoint;
import io.github.atengk.boot.tcp.server.TcpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * TCP 车载长连接端到端集成测试沙箱
 * <p>
 * 100% 离线自洽，基于动态随机端口、内存车机仿真与完整 MockMvc Servlet 契约管线完成业务闭环验证。
 * </p>
 *
 * @author Ateng
 * @since 2026-10-26
 */
@SpringBootTest(classes = SampleTcpApplication.class)
@AutoConfigureMockMvc
@ActiveProfiles("test")
class SampleTcpE2eTest {

    @Autowired
    private TcpServer tcpServer;

    @Autowired
    private VehicleControlService vehicleControlService;

    @Autowired
    private MockMvc mockMvc;

    @Autowired(required = false)
    private TcpActuatorEndpoint tcpActuatorEndpoint;

    private VehicleSimulator simulator1;
    private VehicleSimulator simulator2;
    private VehicleSimulator simulatorRecon;

    @AfterEach
    void tearDown() {
        if (simulator1 != null) {
            simulator1.stop();
        }
        if (simulator2 != null) {
            simulator2.stop();
        }
        if (simulatorRecon != null) {
            simulatorRecon.stop();
        }
    }

    @Test
    @DisplayName("端到端全真验证：连接登录 -> 定位上报自动ACK -> 远程控制指令 -> 全双工RPC下发 -> 广播预警 -> 断网自愈重连 -> 踢旧留新 -> MockMvc管线")
    void testEndToEndVehicleScenario() throws Exception {
        assertThat(tcpServer.isRunning()).isTrue();
        int serverPort = tcpServer.getActualPort();
        assertThat(serverPort).isGreaterThan(0);

        String deviceId = "VEHICLE-E2E-8888";

        // 1. 启动车机终端模拟器并建立物理长连接
        simulator1 = new VehicleSimulator(deviceId, "127.0.0.1", serverPort);
        simulator1.start();
        await().until(simulator1::isConnected);

        // 2. 模拟发送登录包，完成设备标识绑定
        simulator1.login();
        await().untilAsserted(() -> {
            VehicleStatusVO status = vehicleControlService.getVehicleStatus(deviceId);
            assertThat(status).isNotNull();
            assertThat(status.getOnline()).isTrue();
        });

        // 3. 模拟上报定位遥测数据包（包含 engineStatus），验证服务端自动返回 LOCATION_ACK 回执
        long reportSeq = 9901L;
        simulator1.reportLocation(reportSeq, 116.4074, 39.9042, 72.5, "ON");
        await().untilAsserted(() -> {
            VehicleStatusVO status = vehicleControlService.getVehicleStatus(deviceId);
            assertThat(status).isNotNull();
            assertThat(status.getLastSpeed()).isEqualTo(72.5);
            assertThat(status.getEngineStatus()).isEqualTo("ON");
            assertThat(status.getLastLongitude()).isEqualTo(116.4074);
            assertThat(status.getLastLatitude()).isEqualTo(39.9042);
            // 验证车机收到了 @TcpMapping 自动回写的 LOCATION_ACK 回执
            assertThat(simulator1.getReceivedAcks()).contains("LOCATION_ACK");
        });

        // 4. 下发单车远程控制指令（LOCK / UNLOCK），验证全双工回执与执行闭环
        VehicleCommandRequest lockCmd = new VehicleCommandRequest(deviceId, "LOCK", Map.of("mode", "SAFE"));
        mockMvc.perform(post("/api/tcp/vehicle/control")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(JsonUtil.toJsonString(lockCmd)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data.command").value("LOCK"))
                .andExpect(jsonPath("$.data.status").value("SUCCESS"));
        assertThat(simulator1.getExecutedCommands()).contains("LOCK");

        // 5. 下发全双工 RPC 参数查询指令，终端自动应答并验证 MockMvc 返回
        VehicleParamQueryRequest queryReq = new VehicleParamQueryRequest(deviceId, "SYSTEM");
        mockMvc.perform(post("/api/tcp/vehicle/query-params")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(JsonUtil.toJsonString(queryReq)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data.deviceId").value(deviceId))
                .andExpect(jsonPath("$.data.firmwareVersion").value("v2.1.0-release"))
                .andExpect(jsonPath("$.data.batteryLevel").value(88))
                .andExpect(jsonPath("$.data.status").value("NORMAL"));

        // 6. 模拟向全网在线终端广播预警消息并验证 Servlet 契约
        String alertMsg = "【预警】前方路段严重拥堵，建议绕行G4京港澳高速";
        BroadcastAlertRequest alertReq = new BroadcastAlertRequest(alertMsg);
        mockMvc.perform(post("/api/tcp/vehicle/broadcast-alert")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(JsonUtil.toJsonString(alertReq)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200));
        await().untilAsserted(() -> {
            assertThat(simulator1.getReceivedAlerts()).contains(alertMsg);
        });

        // 7. 验证 Web Controller Servlet 真实管线（TraceFilter、状态码与 JSR-303 防卫）
        mockMvc.perform(get("/api/tcp/vehicle/status").param("deviceId", deviceId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data.deviceId").value(deviceId))
                .andExpect(jsonPath("$.data.online").value(true))
                .andExpect(jsonPath("$.data.engineStatus").value("ON"));

        mockMvc.perform(get("/api/tcp/vehicle/events"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data").isArray());

        // 8. 验证 Actuator /actuator/tcp 管控端点信息诊断
        if (tcpActuatorEndpoint != null) {
            Map<String, Object> info = tcpActuatorEndpoint.info();
            assertThat(info.get("status")).isEqualTo("UP");
            assertThat(info.get("activeSessions")).isInstanceOf(Number.class);

            Map<String, Object> detail = tcpActuatorEndpoint.sessionDetail(deviceId);
            assertThat(detail.get("deviceId")).isEqualTo(deviceId);
            assertThat(detail.get("active")).isEqualTo(true);
        }

        // 9. 模拟网络闪断后新连接接入：验证“踢旧留新”机制
        simulator2 = new VehicleSimulator(deviceId, "127.0.0.1", serverPort);
        simulator2.start();
        await().until(simulator2::isConnected);

        simulator2.login();
        await().untilAsserted(() -> {
            assertThat(simulator1.isConnected()).isFalse();
            assertThat(simulator2.isConnected()).isTrue();
        });

        // 10. 验证客户端后台自愈重连能力
        String reconDeviceId = "VEHICLE-RECON-777";
        simulatorRecon = new VehicleSimulator(reconDeviceId, "127.0.0.1", serverPort, true);
        simulatorRecon.start();
        await().until(simulatorRecon::isConnected);
        simulatorRecon.login();

        // 人为切断底层网络通道
        simulatorRecon.getTcpClient().disconnect();
        await().until(() -> !simulatorRecon.isConnected());

        // 验证客户端后台指数退避守护进程自动重连恢复
        await().until(simulatorRecon::isConnected);
    }
}
