package io.github.atengk.boot.sample.tcp;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.atengk.boot.core.context.TenantContextHolder;
import io.github.atengk.boot.core.context.UserContextHolder;
import io.github.atengk.boot.sample.tcp.dto.ClientRpcRequest;
import io.github.atengk.boot.sample.tcp.dto.ClientSendRequest;
import io.github.atengk.boot.sample.tcp.dto.ClusterDispatchRequest;
import io.github.atengk.boot.sample.tcp.dto.GroupSendRequest;
import io.github.atengk.boot.sample.tcp.dto.Jt808FrameDecodeRequest;
import io.github.atengk.boot.sample.tcp.dto.Jt808FrameEncodeRequest;
import io.github.atengk.boot.sample.tcp.dto.ServerBroadcastRequest;
import io.github.atengk.boot.sample.tcp.dto.SessionBackpressureRequest;
import io.github.atengk.boot.sample.tcp.dto.SessionBindRequest;
import io.github.atengk.boot.sample.tcp.dto.SessionGroupRequest;
import io.github.atengk.boot.sample.tcp.dto.SessionKickRequest;
import io.github.atengk.boot.sample.tcp.dto.SpiProbeRequest;
import io.github.atengk.boot.sample.tcp.dto.TelemetryReportRequest;
import io.github.atengk.boot.sample.tcp.dto.TrafficShapingAdjustRequest;
import io.github.atengk.boot.sample.tcp.dto.VehicleControlRequest;
import io.github.atengk.boot.sample.tcp.dto.VehicleParamQueryRequest;
import io.github.atengk.boot.sample.tcp.simulator.FleetSimulationRunner;
import io.github.atengk.boot.sample.tcp.simulator.VehicleSimulator;
import io.github.atengk.boot.tcp.client.TcpClientRegistry;
import io.github.atengk.boot.tcp.enums.BackpressurePolicy;
import io.github.atengk.boot.tcp.server.TcpServerRegistry;
import io.github.atengk.boot.tcp.session.TcpSessionManager;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.io.IOException;
import java.net.ServerSocket;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.not;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.asyncDispatch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.request;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * TCP Sample Web 控制器全场景 MockMvc 双模集成测试
 * <p>
 * 覆盖 5 大领域 Controller（服务端多端口、多命名客户端与 mTLS、会话与分组治理、
 * JT/T 808 与同步 RPC 协议栈、SPI/背压/流量整形/集群桥接治理），包含 JSR-303 参数校验拦截、
 * {@code Result<T>} 强类型响应契约、Actuator 端点探针与双模隔离自适应验证。
 * </p>
 *
 * @author Ateng
 * @since 2026-11-11
 */
@SpringBootTest(classes = SampleTcpApplication.class)
@AutoConfigureMockMvc
@DisplayName("TCP Sample 5 大领域 Web 控制器全场景 MockMvc 集成测试")
class SampleTcpWebControllerTest {

    private static boolean isLocalProfileActive() {
        String activeProfiles = System.getProperty("spring.profiles.active");
        if (activeProfiles != null && activeProfiles.contains("local")) {
            return true;
        }
        String envProfiles = System.getenv("SPRING_PROFILES_ACTIVE");
        return envProfiles != null && envProfiles.contains("local");
    }

    @DynamicPropertySource
    static void bindLoopbackDynamicPorts(DynamicPropertyRegistry registry) {
        if (isLocalProfileActive()) {
            return;
        }
        int[] ports = allocateDistinctFreePorts(3);
        int mainPort = ports[0];
        int binaryPort = ports[1];
        int tlsPort = ports[2];

        registry.add("ateng.boot.tcp.server.port", () -> mainPort);
        registry.add("ateng.boot.tcp.client.port", () -> mainPort);
        registry.add("ateng.boot.tcp.servers.binary-jt808.port", () -> binaryPort);
        registry.add("ateng.boot.tcp.clients.binary-client.port", () -> binaryPort);
        registry.add("ateng.boot.tcp.servers.tls-gateway.port", () -> tlsPort);
        registry.add("ateng.boot.tcp.clients.tls-client.port", () -> tlsPort);
    }

    private static int[] allocateDistinctFreePorts(int count) {
        int[] ports = new int[count];
        ServerSocket[] sockets = new ServerSocket[count];
        try {
            for (int i = 0; i < count; i++) {
                sockets[i] = new ServerSocket(0);
                ports[i] = sockets[i].getLocalPort();
            }
            return ports;
        } catch (IOException ex) {
            throw new IllegalStateException("分配随机可用 TCP 端口失败", ex);
        } finally {
            for (ServerSocket socket : sockets) {
                if (socket != null) {
                    try {
                        socket.close();
                    } catch (IOException ignored) {
                    }
                }
            }
        }
    }

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private TcpServerRegistry tcpServerRegistry;

    @Autowired
    private TcpClientRegistry tcpClientRegistry;

    @Autowired
    private TcpSessionManager sessionManager;

    @BeforeEach
    void awaitInfrastructureReady() {
        UserContextHolder.clear();
        TenantContextHolder.clear();
        MDC.clear();

        if (!isLocalProfileActive()) {
            await().atMost(5, TimeUnit.SECONDS).until(() ->
                    tcpClientRegistry.getRequiredClient("default").isConnected()
                            && tcpClientRegistry.getRequiredClient("binary-client").isConnected()
                            && tcpClientRegistry.getRequiredClient("tls-client").isConnected()
                            && sessionManager.getSessionByDeviceId(FleetSimulationRunner.DEVICE_ALPHA_001) != null
                            && sessionManager.getSessionByDeviceId(FleetSimulationRunner.DEVICE_BETA_002) != null
            );
        }
    }

    @AfterEach
    void cleanContext() {
        UserContextHolder.clear();
        TenantContextHolder.clear();
        MDC.clear();
    }

    @Test
    @DisplayName("1. TcpServerController: 多端口实例清单（含 TLS/mTLS 与防火墙）、广播及 JSR-303 拦截")
    void testTcpServerController() throws Exception {
        mockMvc.perform(get("/sample/tcp/server/list"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data[*].name", hasItem("default")))
                .andExpect(jsonPath("$.data[*].name", hasItem("binary-jt808")))
                .andExpect(jsonPath("$.data[*].name", hasItem("tls-gateway")));

        mockMvc.perform(get("/sample/tcp/server/detail").param("serverName", "tls-gateway"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data.name").value("tls-gateway"))
                .andExpect(jsonPath("$.data.sslEnabled").value(true))
                .andExpect(jsonPath("$.data.clientAuth").value("NEED"));

        mockMvc.perform(get("/sample/tcp/server/detail").param("serverName", "non-existent-server"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.code").value(not(200)));

        ServerBroadcastRequest broadcastReq = ServerBroadcastRequest.builder()
                .message("WEATHER_ALERT_TYPHOON")
                .cluster(false)
                .build();
        mockMvc.perform(post("/sample/tcp/server/broadcast")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(broadcastReq)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data.scope").value("BROADCAST"))
                .andExpect(jsonPath("$.data.dispatched").value(true));

        GroupSendRequest groupReq = GroupSendRequest.builder()
                .group(FleetSimulationRunner.GROUP_ALPHA)
                .message("FLEET_DISPATCH_ORDER_001")
                .cluster(false)
                .build();
        mockMvc.perform(post("/sample/tcp/server/group-send")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(groupReq)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data.target").value(FleetSimulationRunner.GROUP_ALPHA));

        // JSR-303 空内容快速失败
        ServerBroadcastRequest invalidBroadcast = ServerBroadcastRequest.builder().message("").build();
        mockMvc.perform(post("/sample/tcp/server/broadcast")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(invalidBroadcast)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.code").value(not(200)));
    }

    @Test
    @DisplayName("2. TcpClientController: 多客户端（含 mTLS 客户端）推流、同步/异步 RPC 与重连恢复")
    void testTcpClientController() throws Exception {
        mockMvc.perform(get("/sample/tcp/client/list"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data[*].name", hasItem("default")))
                .andExpect(jsonPath("$.data[*].name", hasItem("binary-client")))
                .andExpect(jsonPath("$.data[*].name", hasItem("tls-client")));

        // 使用 mTLS 加密通道客户端推送 JSON 载荷
        ClientSendRequest tlsSend = ClientSendRequest.builder()
                .clientName("tls-client")
                .payloadType("JSON")
                .command("HEARTBEAT")
                .content("TLS_SECURE_HEARTBEAT")
                .build();
        mockMvc.perform(post("/sample/tcp/client/send")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(tlsSend)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data.clientName").value("tls-client"))
                .andExpect(jsonPath("$.data.sent").value(true));

        // 使用 binary-client 推送 HEX 二进制报文
        ClientSendRequest hexSend = ClientSendRequest.builder()
                .clientName("binary-client")
                .payloadType("HEX")
                .command("AUDIT_PING")
                .content("48454C4C4F")
                .build();
        mockMvc.perform(post("/sample/tcp/client/send")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(hexSend)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data.payloadBytes").value(5));

        // 同步 RPC 上行（default 客户端 -> HEARTBEAT）
        ClientRpcRequest syncRpc = ClientRpcRequest.builder()
                .clientName("default")
                .command("HEARTBEAT")
                .timeoutMillis(3000L)
                .build();
        mockMvc.perform(post("/sample/tcp/client/request")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(syncRpc)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data.responseText", containsString("HEARTBEAT_ACK")));

        // 异步非阻塞 RPC（mTLS tls-client -> HEARTBEAT）
        ClientRpcRequest asyncRpc = ClientRpcRequest.builder()
                .clientName("tls-client")
                .command("HEARTBEAT")
                .timeoutMillis(3000L)
                .asyncMode(true)
                .build();
        MvcResult mvcResult = mockMvc.perform(post("/sample/tcp/client/request-async")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(asyncRpc)))
                .andExpect(request().asyncStarted())
                .andReturn();
        mockMvc.perform(asyncDispatch(mvcResult))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data.asyncMode").value(true))
                .andExpect(jsonPath("$.data.responseText", containsString("HEARTBEAT_ACK")));

        // 手动断开与显式重连恢复（在 binary-client 上验证后恢复在线态）
        mockMvc.perform(post("/sample/tcp/client/disconnect").param("clientName", "binary-client"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data.connected").value(false));

        mockMvc.perform(post("/sample/tcp/client/reconnect").param("clientName", "binary-client"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data.connected").value(true));

        // JSR-303 非法载荷格式校验拦截
        ClientSendRequest invalidSend = ClientSendRequest.builder()
                .clientName("default")
                .payloadType("INVALID_TYPE")
                .content("hello")
                .build();
        mockMvc.perform(post("/sample/tcp/client/send")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(invalidSend)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.code").value(not(200)));

        // JSR-303 @NotNull 拦截空 timeoutMillis
        ClientRpcRequest nullTimeoutRpc = ClientRpcRequest.builder()
                .clientName("default")
                .command("HEARTBEAT")
                .timeoutMillis(null)
                .build();
        mockMvc.perform(post("/sample/tcp/client/request")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(nullTimeoutRpc)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.code").value(not(200)));
    }

    @Test
    @DisplayName("3. TcpSessionController: 活跃会话列表、聚合详情、分组管理与独立临时连接绑定/强踢")
    void testTcpSessionController() throws Exception {
        mockMvc.perform(get("/sample/tcp/session/list").param("group", FleetSimulationRunner.GROUP_ALPHA))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data[*].deviceId", hasItem(FleetSimulationRunner.DEVICE_ALPHA_001)));

        mockMvc.perform(get("/sample/tcp/session/detail").param("deviceId", FleetSimulationRunner.DEVICE_ALPHA_001))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data.session.deviceId").value(FleetSimulationRunner.DEVICE_ALPHA_001))
                .andExpect(jsonPath("$.data.session.active").value(true))
                .andExpect(jsonPath("$.data.telemetry.online").value(true));

        // 临时加入与退出车队分组并保持状态还原
        try {
            SessionGroupRequest joinReq = SessionGroupRequest.builder()
                    .deviceId(FleetSimulationRunner.DEVICE_ALPHA_001)
                    .group("FLEET-TEMP-AUDIT")
                    .action("JOIN")
                    .build();
            mockMvc.perform(post("/sample/tcp/session/group")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(joinReq)))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.code").value(200))
                    .andExpect(jsonPath("$.data").value(true));

            mockMvc.perform(get("/sample/tcp/session/list").param("group", "FLEET-TEMP-AUDIT"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.code").value(200))
                    .andExpect(jsonPath("$.data[*].deviceId", hasItem(FleetSimulationRunner.DEVICE_ALPHA_001)));
        } finally {
            SessionGroupRequest leaveReq = SessionGroupRequest.builder()
                    .deviceId(FleetSimulationRunner.DEVICE_ALPHA_001)
                    .group("FLEET-TEMP-AUDIT")
                    .action("LEAVE")
                    .build();
            mockMvc.perform(post("/sample/tcp/session/group")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(leaveReq)))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data").value(true));

            mockMvc.perform(get("/sample/tcp/session/list").param("group", "FLEET-TEMP-AUDIT"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.code").value(200))
                    .andExpect(jsonPath("$.data").isEmpty());
        }

        // 启动独立一次性仿真车机验证 /bind 与 /kick，绝不干扰共享车队 VEHICLE-001/002
        int mainPort = tcpServerRegistry.getRequiredServer("default").getActualPort();
        String tempOriginalDevice = "VEHICLE-TMP-BIND-ORIG";
        String tempReboundDevice = "VEHICLE-TMP-KICK-TARGET";
        VehicleSimulator disposableSim = new VehicleSimulator(tempOriginalDevice, "127.0.0.1", mainPort, false);
        try {
            disposableSim.start();
            await().until(disposableSim::isConnected);
            disposableSim.login();
            await().until(() -> sessionManager.getSessionByDeviceId(tempOriginalDevice) != null);
            String tempSessionId = sessionManager.getSessionByDeviceId(tempOriginalDevice).getSessionId();

            SessionBindRequest bindReq = SessionBindRequest.builder()
                    .sessionId(tempSessionId)
                    .deviceId(tempReboundDevice)
                    .build();
            mockMvc.perform(post("/sample/tcp/session/bind")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(bindReq)))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.code").value(200))
                    .andExpect(jsonPath("$.data.deviceId").value(tempReboundDevice));

            SessionKickRequest kickReq = SessionKickRequest.builder()
                    .deviceId(tempReboundDevice)
                    .reason("ADMIN_AUDIT_KICK")
                    .build();
            mockMvc.perform(post("/sample/tcp/session/kick")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(kickReq)))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.code").value(200))
                    .andExpect(jsonPath("$.data").value(true));

            await().atMost(3, TimeUnit.SECONDS).until(() -> sessionManager.getSessionByDeviceId(tempReboundDevice) == null);
        } finally {
            disposableSim.stop();
        }

        // JSR-303 @AssertTrue 交叉校验拦截（deviceId 与 sessionId 同空）
        SessionKickRequest invalidKick = SessionKickRequest.builder().reason("EMPTY").build();
        mockMvc.perform(post("/sample/tcp/session/kick")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(invalidKick)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.code").value(not(200)));
    }

    @Test
    @DisplayName("4. TcpProtocolController: 车控 RPC、参数查询、JT/T 808 编解码篡改拦截与实时定位上行")
    void testTcpProtocolController() throws Exception {
        VehicleControlRequest controlReq = VehicleControlRequest.builder()
                .deviceId(FleetSimulationRunner.DEVICE_ALPHA_001)
                .command("LOCK")
                .params(Map.of("securityMode", "HIGH"))
                .build();
        mockMvc.perform(post("/sample/tcp/protocol/control")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(controlReq)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data.deviceId").value(FleetSimulationRunner.DEVICE_ALPHA_001))
                .andExpect(jsonPath("$.data.status").value("SUCCESS"));

        VehicleParamQueryRequest queryReq = VehicleParamQueryRequest.builder()
                .deviceId(FleetSimulationRunner.DEVICE_BETA_002)
                .paramType("BATTERY")
                .build();
        mockMvc.perform(post("/sample/tcp/protocol/param-query")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(queryReq)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data.deviceId").value(FleetSimulationRunner.DEVICE_BETA_002))
                .andExpect(jsonPath("$.data.status").value("NORMAL"));

        // JT/T 808 编解码往返校验
        Jt808FrameEncodeRequest encodeReq = Jt808FrameEncodeRequest.builder()
                .deviceId("888888")
                .seq(2048L)
                .longitude(116.407412)
                .latitude(39.904231)
                .speed(92.5)
                .engineStatus("ON")
                .build();
        MvcResult encodeResult = mockMvc.perform(post("/sample/tcp/protocol/jt808/encode")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(encodeReq)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data.routeKey").value("JT808_LOCATION"))
                .andExpect(jsonPath("$.data.bccHex").isNotEmpty())
                .andExpect(jsonPath("$.data.crc16ModbusHex").isNotEmpty())
                .andReturn();

        String hexFrame = objectMapper.readTree(encodeResult.getResponse().getContentAsString())
                .path("data").path("hexFrame").asText();
        Jt808FrameDecodeRequest decodeReq = Jt808FrameDecodeRequest.builder().hexFrame(hexFrame).build();
        mockMvc.perform(post("/sample/tcp/protocol/jt808/decode")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(decodeReq)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data.deviceId").value("888888"))
                .andExpect(jsonPath("$.data.seq").value("2048"));

        // 篡改十六进制帧触发 BCC 校验失败转译
        Jt808FrameDecodeRequest tamperedReq = Jt808FrameDecodeRequest.builder().hexFrame("0102030405").build();
        mockMvc.perform(post("/sample/tcp/protocol/jt808/decode")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(tamperedReq)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.code").value(not(200)));

        // 实时上行 JT808 二进制遥测演练
        TelemetryReportRequest telemetryReq = TelemetryReportRequest.builder()
                .deviceId(FleetSimulationRunner.DEVICE_BETA_002)
                .protocol("JT808")
                .longitude(121.480000)
                .latitude(31.240000)
                .speed(84.5)
                .engineStatus("ON")
                .build();
        mockMvc.perform(post("/sample/tcp/protocol/report")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(telemetryReq)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data.lastSpeed").value(84.5));
    }

    @Test
    @DisplayName("5. TcpGovernanceController & Actuator: SPI 可观测、2 参背压调节、流量整形调优、集群桥接与探针")
    void testTcpGovernanceAndActuator() throws Exception {
        String expectedBridge = isLocalProfileActive() ? "RedisPubSubTcpClusterBridge" : "LocalTcpClusterBridge";

        // 触发 SPI 关联 + 函数式处理器探针
        SpiProbeRequest rpcProbe = SpiProbeRequest.builder()
                .mode("AUDIT_RPC")
                .note("SAMPLE_AUDIT_VERIFY")
                .build();
        MvcResult probeRes = mockMvc.perform(post("/sample/tcp/governance/spi-probe")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(rpcProbe)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data.replyPayload", containsString("AUDIT_PONG")))
                .andReturn();
        var probeData = objectMapper.readTree(probeRes.getResponse().getContentAsString()).path("data");
        assertThat(probeData.path("correlationExtractedCount").asLong()).isGreaterThanOrEqualTo(1L);
        assertThat(probeData.path("auditPingHandledCount").asLong()).isGreaterThanOrEqualTo(1L);

        // 触发入站包过滤器拦截探针
        SpiProbeRequest blockProbe = SpiProbeRequest.builder()
                .mode("FILTER_BLOCK")
                .note("MALICIOUS_FRAME_TEST")
                .build();
        MvcResult blockRes = mockMvc.perform(post("/sample/tcp/governance/spi-probe")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(blockProbe)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andReturn();
        assertThat(objectMapper.readTree(blockRes.getResponse().getContentAsString())
                .path("data").path("inboundBlockedCount").asLong()).isGreaterThanOrEqualTo(1L);

        // 全链路治理总览巡检
        MvcResult overviewRes = mockMvc.perform(get("/sample/tcp/governance/overview"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data.clusterBridgeType").value(expectedBridge))
                .andExpect(jsonPath("$.data.registeredPacketFilters", hasItem("SampleTcpPacketFilter")))
                .andExpect(jsonPath("$.data.registeredChannelCustomizers", hasItem("SampleTcpChannelCustomizer")))
                .andExpect(jsonPath("$.data.registeredMessageHandlers", hasItem("SampleAuditMessageHandler")))
                .andExpect(jsonPath("$.data.registeredCorrelationExtractors", hasItem("SampleTcpCorrelationIdExtractor")))
                .andExpect(jsonPath("$.data.registeredRouteKeyExtractors", hasItem("Jt808RouteKeyExtractor")))
                .andReturn();
        assertThat(objectMapper.readTree(overviewRes.getResponse().getContentAsString())
                .path("data").path("serverChannelsCustomized").asLong()).isGreaterThanOrEqualTo(1L);

        // 动态调整会话背压（调用 TcpSession 两参重载）并在 finally 恢复默认策略
        try {
            SessionBackpressureRequest bpReq = SessionBackpressureRequest.builder()
                    .deviceId(FleetSimulationRunner.DEVICE_ALPHA_001)
                    .policy(BackpressurePolicy.BLOCK_TIMEOUT)
                    .timeoutMillis(750L)
                    .build();
            mockMvc.perform(post("/sample/tcp/governance/backpressure")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(bpReq)))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.code").value(200))
                    .andExpect(jsonPath("$.data.backpressurePolicy").value("BLOCK_TIMEOUT"))
                    .andExpect(jsonPath("$.data.backpressureTimeoutMs").value("750"));
        } finally {
            SessionBackpressureRequest restoreBp = SessionBackpressureRequest.builder()
                    .deviceId(FleetSimulationRunner.DEVICE_ALPHA_001)
                    .policy(BackpressurePolicy.FAIL_FAST)
                    .timeoutMillis(3000L)
                    .build();
            mockMvc.perform(post("/sample/tcp/governance/backpressure")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(restoreBp)))
                    .andExpect(status().isOk());
        }

        // 动态调整流量整形阈值并还原
        try {
            TrafficShapingAdjustRequest tsReq = TrafficShapingAdjustRequest.builder()
                    .serverName("default")
                    .writeLimitBytesPerSec(5_242_880L)
                    .readLimitBytesPerSec(5_242_880L)
                    .build();
            mockMvc.perform(post("/sample/tcp/governance/traffic-shaping")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(tsReq)))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.code").value(200))
                    .andExpect(jsonPath("$.data.writeLimitBytesPerSec").value("5242880"))
                    .andExpect(jsonPath("$.data.readLimitBytesPerSec").value("5242880"));
        } finally {
            TrafficShapingAdjustRequest restoreTs = TrafficShapingAdjustRequest.builder()
                    .serverName("default")
                    .writeLimitBytesPerSec(10_485_760L)
                    .readLimitBytesPerSec(10_485_760L)
                    .build();
            mockMvc.perform(post("/sample/tcp/governance/traffic-shaping")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(restoreTs)))
                    .andExpect(status().isOk());
        }

        // 集群桥接分发（GROUP & BROADCAST）及非广播缺 target 校验拦截
        ClusterDispatchRequest groupCluster = ClusterDispatchRequest.builder()
                .mode("GROUP")
                .target(FleetSimulationRunner.GROUP_ALPHA)
                .message("CLUSTER_GROUP_NOTICE")
                .build();
        mockMvc.perform(post("/sample/tcp/governance/cluster-broadcast")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(groupCluster)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data.bridgeType").value(expectedBridge))
                .andExpect(jsonPath("$.data.dispatched").value(true));

        ClusterDispatchRequest invalidCluster = ClusterDispatchRequest.builder()
                .mode("DEVICE")
                .message("MISSING_TARGET")
                .build();
        mockMvc.perform(post("/sample/tcp/governance/cluster-broadcast")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(invalidCluster)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.code").value(not(200)));

        // Actuator 运维端点探针验证
        mockMvc.perform(get("/actuator/tcp"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("UP"));

        mockMvc.perform(get("/actuator/health"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("UP"));
    }
}
