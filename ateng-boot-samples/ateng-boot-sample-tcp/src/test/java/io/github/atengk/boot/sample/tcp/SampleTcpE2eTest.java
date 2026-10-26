package io.github.atengk.boot.sample.tcp;

import io.github.atengk.boot.core.model.Result;
import io.github.atengk.boot.sample.tcp.controller.VehicleScenarioController;
import io.github.atengk.boot.sample.tcp.model.VehicleParamQueryAck;
import io.github.atengk.boot.sample.tcp.model.VehicleStatusVO;
import io.github.atengk.boot.sample.tcp.service.VehicleControlService;
import io.github.atengk.boot.sample.tcp.simulator.VehicleSimulator;
import io.github.atengk.boot.tcp.actuator.TcpActuatorEndpoint;
import io.github.atengk.boot.tcp.server.TcpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

/**
 * TCP 车载长连接端到端集成测试沙箱
 * <p>
 * 100% 离线自洽，基于动态分配的随机端口与内存仿真车机完成端到端全链路业务闭环验证。
 * </p>
 *
 * @author Ateng
 * @since 2026-10-26
 */
@SpringBootTest(classes = SampleTcpApplication.class)
@ActiveProfiles("test")
class SampleTcpE2eTest {

    @Autowired
    private TcpServer tcpServer;

    @Autowired
    private VehicleControlService vehicleControlService;

    @Autowired
    private VehicleScenarioController vehicleScenarioController;

    @Autowired(required = false)
    private TcpActuatorEndpoint tcpActuatorEndpoint;

    private VehicleSimulator simulator1;
    private VehicleSimulator simulator2;

    @AfterEach
    void tearDown() {
        if (simulator1 != null) {
            simulator1.stop();
        }
        if (simulator2 != null) {
            simulator2.stop();
        }
    }

    @Test
    @DisplayName("端到端全真验证：车机连接登录 -> 定位上报 -> 全双工RPC下发 -> 广播预警 -> 踢旧留新 -> Actuator端点")
    void testEndToEndVehicleScenario() {
        assertThat(tcpServer.isRunning()).isTrue();
        int serverPort = tcpServer.getActualPort();
        assertThat(serverPort).isGreaterThan(0);

        String deviceId = "VEHICLE-E2E-8888";

        // 1. 启动车机终端模拟器并建立连接
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

        // 3. 模拟上报定位遥测数据包
        long reportSeq = 9901L;
        simulator1.reportLocation(reportSeq, 116.4074, 39.9042, 72.5);
        await().untilAsserted(() -> {
            VehicleStatusVO status = vehicleControlService.getVehicleStatus(deviceId);
            assertThat(status.getLastSpeed()).isEqualTo(72.5);
            assertThat(status.getLastLongitude()).isEqualTo(116.4074);
            assertThat(status.getLastLatitude()).isEqualTo(39.9042);
        });

        // 4. 服务端调用全双工 RPC 下发参数查询指令，仿真终端自动应答回执
        VehicleParamQueryAck ack = vehicleControlService.queryDeviceParams(deviceId, "SYSTEM");
        assertThat(ack).isNotNull();
        assertThat(ack.getDeviceId()).isEqualTo(deviceId);
        assertThat(ack.getFirmwareVersion()).isEqualTo("v2.1.0-release");
        assertThat(ack.getBatteryLevel()).isEqualTo(88);
        assertThat(ack.getStatus()).isEqualTo("NORMAL");

        // 5. 模拟向全网在线终端广播预警消息
        String alertMsg = "【预警】前方路段严重拥堵，建议绕行G4京港澳高速";
        vehicleControlService.broadcastSpeedLimitAlert(alertMsg);
        await().untilAsserted(() -> {
            assertThat(simulator1.getReceivedAlerts()).contains(alertMsg);
        });

        // 6. 验证 Actuator /actuator/tcp 管控端点信息诊断
        if (tcpActuatorEndpoint != null) {
            Map<String, Object> info = tcpActuatorEndpoint.info();
            assertThat(info.get("status")).isEqualTo("UP");
            assertThat(info.get("activeSessions")).isInstanceOf(Number.class);

            Map<String, Object> detail = tcpActuatorEndpoint.sessionDetail(deviceId);
            assertThat(detail.get("deviceId")).isEqualTo(deviceId);
            assertThat(detail.get("active")).isEqualTo(true);
        }

        // 7. 验证 Web Controller 响应契约
        Result<VehicleStatusVO> controllerStatus = vehicleScenarioController.getStatus(deviceId);
        assertThat(controllerStatus.getCode()).isEqualTo(200);
        assertThat(controllerStatus.getData().getDeviceId()).isEqualTo(deviceId);
        assertThat(controllerStatus.getData().getOnline()).isTrue();

        Result<List<String>> eventsResult = vehicleScenarioController.getEvents();
        assertThat(eventsResult.getCode()).isEqualTo(200);
        assertThat(eventsResult.getData()).isNotEmpty();

        // 8. 模拟网络闪断后新连接重连：验证“踢旧留新”机制
        simulator2 = new VehicleSimulator(deviceId, "127.0.0.1", serverPort);
        simulator2.start();
        await().until(simulator2::isConnected);

        // simulator2 同样发送该 deviceId 登录
        simulator2.login();
        await().untilAsserted(() -> {
            // 旧终端连接已被切断
            assertThat(simulator1.isConnected()).isFalse();
            // 新终端保持活跃
            assertThat(simulator2.isConnected()).isTrue();
        });
    }
}
