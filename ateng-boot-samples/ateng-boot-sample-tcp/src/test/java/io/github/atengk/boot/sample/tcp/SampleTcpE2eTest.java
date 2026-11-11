package io.github.atengk.boot.sample.tcp;

import io.github.atengk.boot.sample.tcp.model.LocationReportPacket;
import io.github.atengk.boot.sample.tcp.model.VehicleControlAck;
import io.github.atengk.boot.sample.tcp.model.VehicleParamQueryAck;
import io.github.atengk.boot.sample.tcp.model.VehicleStatusVO;
import io.github.atengk.boot.sample.tcp.service.VehicleTelemetryScenarioService;
import io.github.atengk.boot.sample.tcp.simulator.VehicleSimulator;
import io.github.atengk.boot.tcp.actuator.TcpActuatorEndpoint;
import io.github.atengk.boot.tcp.server.TcpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

/**
 * TCP 车载长连接全场景端到端集成测试沙箱（Non-Web 协议沙箱轨道）
 * <p>
 * 遵循 ADR-0105 双轨并存验收机制，以 {@code WebEnvironment.NONE} 无 Web 容器形态 100% 离线自洽运行，
 * 基于动态随机端口、内存仿真车机终端与 {@link VehicleTelemetryScenarioService} 完成底层纯协议与多端口拓扑闭环验证。
 * 覆盖车机认证、JT/T 808 国标复合报文（0x7E/0x7D/BCC）、全双工 RPC、车队分组组播、出站背压与高可用自愈。
 * </p>
 *
 * @author Ateng
 * @since 2026-10-26
 */
@SpringBootTest(classes = SampleTcpApplication.class, webEnvironment = SpringBootTest.WebEnvironment.NONE)
@ActiveProfiles("test")
class SampleTcpE2eTest {

    @Autowired
    private TcpServer tcpServer;

    @Autowired
    private VehicleTelemetryScenarioService scenarioService;

    @Autowired(required = false)
    private TcpActuatorEndpoint tcpActuatorEndpoint;

    private VehicleSimulator sim1;
    private VehicleSimulator sim2;
    private VehicleSimulator simDuplicate;
    private VehicleSimulator simRecon;

    @AfterEach
    void tearDown() {
        if (sim1 != null) {
            sim1.stop();
        }
        if (sim2 != null) {
            sim2.stop();
        }
        if (simDuplicate != null) {
            simDuplicate.stop();
        }
        if (simRecon != null) {
            simRecon.stop();
        }
        if (scenarioService != null) {
            scenarioService.reset();
        }
    }

    @Test
    @DisplayName("端到端全真验证：接入认证 -> JT/T 808国标复合报文(0x7E/0x7D/BCC) -> JSON定位 -> 心跳 -> 全双工RPC -> 车队分组组播 -> 广播 -> 背压感知 -> 踢旧留新 -> 自愈重连 -> Actuator诊断")
    void testEndToEndVehicleScenario() throws Exception {
        assertThat(tcpServer.isRunning()).isTrue();
        int serverPort = tcpServer.getActualPort();
        assertThat(serverPort).isGreaterThan(0);

        String deviceId1 = "VEHICLE-001";
        String deviceId2 = "VEHICLE-002";

        // 1. 启动车机仿真器 1 并建立长连接
        sim1 = new VehicleSimulator(deviceId1, "127.0.0.1", serverPort);
        sim1.start();
        await().until(sim1::isConnected);

        // 2. 模拟发送登录认证包，完成设备标识绑定
        sim1.login();
        await().untilAsserted(() -> {
            VehicleStatusVO status = scenarioService.getVehicleStatus(deviceId1);
            assertThat(status).isNotNull();
            assertThat(status.getOnline()).isTrue();
        });

        // 3. 验证交通部 JT/T 808 国标风格复合分帧上报（含 0x7E 分隔符、0x7D 转义还原与 BCC 校验和验证）
        long jt808Seq = 1001L;
        LocationReportPacket jt808Packet = new LocationReportPacket(
                deviceId1,
                jt808Seq,
                116.407412,
                39.904231,
                85.5,
                "ON",
                System.currentTimeMillis()
        );
        sim1.reportJt808Location(jt808Packet);
        await().untilAsserted(() -> {
            VehicleStatusVO status = scenarioService.getVehicleStatus(deviceId1);
            assertThat(status).isNotNull();
            assertThat(status.getLastSpeed()).isEqualTo(85.5);
            assertThat(status.getEngineStatus()).isEqualTo("ON");
            assertThat(status.getLastLongitude()).isEqualTo(116.407412);
            assertThat(status.getLastLatitude()).isEqualTo(39.904231);
            assertThat(sim1.getReceivedAcks()).contains("JT808_LOCATION_ACK");
        });

        // 3.1 负向防卫验证：上报 BCC 异或校验码损坏的 JT/T 808 报文，服务端拦截拒绝并不更新非法车速
        sim1.reportCorruptedJt808Location(1002L);
        // 等待处理完毕后，验证有效状态依然保持合法上报的数据
        VehicleStatusVO statusAfterCorrupted = scenarioService.getVehicleStatus(deviceId1);
        assertThat(statusAfterCorrupted.getLastSpeed()).isEqualTo(85.5);

        // 4. 模拟标准 JSON 格式高频定位上报，验证服务端自动返回 LOCATION_ACK 回执
        long jsonSeq = 2001L;
        sim1.reportLocation(jsonSeq, 121.473701, 31.230416, 68.0, "ON");
        await().untilAsserted(() -> {
            VehicleStatusVO status = scenarioService.getVehicleStatus(deviceId1);
            assertThat(status).isNotNull();
            assertThat(status.getLastSpeed()).isEqualTo(68.0);
            assertThat(status.getLastLongitude()).isEqualTo(121.473701);
            assertThat(status.getLastLatitude()).isEqualTo(31.230416);
            assertThat(sim1.getReceivedAcks()).contains("LOCATION_ACK");
        });

        // 5. 模拟终端心跳保活
        sim1.sendHeartbeat();
        await().untilAsserted(() -> {
            assertThat(sim1.getReceivedAcks()).contains("HEARTBEAT_ACK");
        });

        // 6. 下发全双工单车远程控制指令（LOCK / UNLOCK），验证同步 RPC 回执与执行闭环
        VehicleControlAck controlAck = scenarioService.sendControlCommand(deviceId1, "LOCK", Map.of("securityMode", "HIGH"));
        assertThat(controlAck).isNotNull();
        assertThat(controlAck.getCommand()).isEqualTo("LOCK");
        assertThat(controlAck.getStatus()).isEqualTo("SUCCESS");
        await().untilAsserted(() -> assertThat(sim1.getExecutedCommands()).contains("LOCK"));

        // 7. 下发全双工 RPC 参数查询指令，验证终端自动应答并同步返回结果
        VehicleParamQueryAck queryAck = scenarioService.queryDeviceParams(deviceId1, "SYSTEM");
        assertThat(queryAck).isNotNull();
        assertThat(queryAck.getDeviceId()).isEqualTo(deviceId1);
        assertThat(queryAck.getFirmwareVersion()).isEqualTo("v2.1.0-release");
        assertThat(queryAck.getBatteryLevel()).isEqualTo(88);
        assertThat(queryAck.getStatus()).isEqualTo("NORMAL");

        // 8. 启动车机仿真器 2 并加入不同车队分组，验证集群车队分组组播与跨组严格隔离
        sim2 = new VehicleSimulator(deviceId2, "127.0.0.1", serverPort);
        sim2.start();
        await().until(sim2::isConnected);
        sim2.login();
        await().untilAsserted(() -> {
            VehicleStatusVO status2 = scenarioService.getVehicleStatus(deviceId2);
            assertThat(status2).isNotNull();
            assertThat(status2.getOnline()).isTrue();
        });

        // 将车机 1 分配到 FLEET-ALPHA 车队，车机 2 分配到 FLEET-BETA 车队
        assertThat(scenarioService.assignFleetGroup(deviceId1, "FLEET-ALPHA")).isTrue();
        assertThat(scenarioService.assignFleetGroup(deviceId2, "FLEET-BETA")).isTrue();

        // 向 FLEET-ALPHA 车队发送集群组播调度预警
        String fleetAlertMsg = "【ALPHA车队调度】前方大雾天气，全队开启雾灯并将车速限制在60km/h以内";
        scenarioService.sendFleetGroupAlert("FLEET-ALPHA", fleetAlertMsg);

        // 验证 FLEET-ALPHA 成员 sim1 成功收到该预警，而 FLEET-BETA 成员 sim2 未收到（组播隔离）
        await().untilAsserted(() -> {
            assertThat(sim1.getReceivedFleetAlerts()).contains(fleetAlertMsg);
            assertThat(sim2.getReceivedFleetAlerts()).doesNotContain(fleetAlertMsg);
        });

        // 9. 模拟向全网在线车机广播全局限速预警
        String globalAlertMsg = "【全网紧急通知】暴雨红色预警生效，全路网减速慢行";
        scenarioService.broadcastSpeedLimitAlert(globalAlertMsg);
        await().untilAsserted(() -> {
            assertThat(sim1.getReceivedAlerts()).contains(globalAlertMsg);
            assertThat(sim2.getReceivedAlerts()).contains(globalAlertMsg);
        });

        // 10. 验证出站缓冲区背压感知与健康状态
        assertThat(scenarioService.isOutboundWritable(deviceId1)).isTrue();
        assertThat(scenarioService.getPendingWriteBytes(deviceId1)).isGreaterThanOrEqualTo(0L);

        // 11. 验证 Actuator /actuator/tcp 管控端点信息诊断
        if (tcpActuatorEndpoint != null) {
            Map<String, Object> info = tcpActuatorEndpoint.info();
            assertThat(info.get("status")).isEqualTo("UP");
            assertThat(info.get("activeSessions")).isInstanceOf(Number.class);

            Map<String, Object> detail = tcpActuatorEndpoint.sessionDetail(deviceId1);
            assertThat(detail.get("deviceId")).isEqualTo(deviceId1);
            assertThat(detail.get("active")).isEqualTo(true);
        }

        // 12. 模拟网络异常重连接入：验证“踢旧留新”机制
        simDuplicate = new VehicleSimulator(deviceId1, "127.0.0.1", serverPort);
        simDuplicate.start();
        await().until(simDuplicate::isConnected);
        simDuplicate.login();

        await().untilAsserted(() -> {
            assertThat(sim1.isConnected()).isFalse();
            assertThat(simDuplicate.isConnected()).isTrue();
        });

        // 13. 验证客户端后台自愈重连机制
        String reconDeviceId = "VEHICLE-RECON-777";
        simRecon = new VehicleSimulator(reconDeviceId, "127.0.0.1", serverPort, true);
        simRecon.start();
        await().until(simRecon::isConnected);
        simRecon.login();

        // 人为切断底层网络通道
        simRecon.getTcpClient().disconnect();
        await().until(() -> !simRecon.isConnected());

        // 验证客户端后台指数退避守护进程自动重连恢复
        await().until(simRecon::isConnected);
    }
}
