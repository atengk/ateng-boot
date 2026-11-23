package io.github.atengk.boot.sample.tcp.simulator;

import io.github.atengk.boot.core.code.CommonResultCode;
import io.github.atengk.boot.core.exception.BusinessException;
import io.github.atengk.boot.sample.tcp.model.LocationReportPacket;
import io.github.atengk.boot.sample.tcp.service.VehicleTelemetryScenarioService;
import io.github.atengk.boot.tcp.server.TcpServer;
import io.github.atengk.boot.tcp.server.TcpServerRegistry;
import io.github.atengk.boot.tcp.template.TcpServerTemplate;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.ApplicationListener;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

import java.util.Collections;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.locks.ReentrantLock;

/**
 * 双模离线车联网车队自动仿真引导器（仅在非 local Profile 下激活）
 * 应用启动就绪后自动拉起 2 台模拟车载终端（VEHICLE-001 归属 FLEET-ALPHA、VEHICLE-002 归属 FLEET-BETA），
 * 连入本机主服务端并完成登录绑定、车队分组与初始 JSON/JT808 遥测上报，支撑零依赖开箱联调与 Web 体验。
 * 激活 {@code local} Profile 时自动静默退场，让位给真实物理终端硬件接入。
 *
 * @author Ateng
 * @since 2026-11-11
 */
@Slf4j
@Component
@Profile("!local")
@ConditionalOnProperty(prefix = "sample.tcp.simulator", name = "enabled", havingValue = "true", matchIfMissing = true)
@RequiredArgsConstructor
public class FleetSimulationRunner implements ApplicationListener<ApplicationReadyEvent>, DisposableBean {

    /**
     * 默认仿真车机 1：归属车队 FLEET-ALPHA
     */
    public static final String DEVICE_ALPHA_001 = "VEHICLE-001";
    public static final String GROUP_ALPHA = "FLEET-ALPHA";

    /**
     * 默认仿真车机 2：归属车队 FLEET-BETA
     */
    public static final String DEVICE_BETA_002 = "VEHICLE-002";
    public static final String GROUP_BETA = "FLEET-BETA";

    private static final long WAIT_LOGIN_TIMEOUT_MS = 3000L;

    private final TcpServerRegistry tcpServerRegistry;
    private final TcpServerTemplate tcpServerTemplate;
    private final VehicleTelemetryScenarioService scenarioService;

    private final Map<String, VehicleSimulator> simulators = new ConcurrentHashMap<>();
    private final AtomicBoolean initialized = new AtomicBoolean(false);
    private final AtomicLong seqGenerator = new AtomicLong(100L);
    private final ReentrantLock lifecycleLock = new ReentrantLock();

    @Override
    public void onApplicationEvent(ApplicationReadyEvent event) {
        lifecycleLock.lock();
        try {
            if (initialized.get()) {
                return;
            }
            TcpServer defaultServer = tcpServerRegistry.getDefaultServer();
            if (defaultServer == null || !defaultServer.isRunning() || defaultServer.getActualPort() <= 0) {
                log.warn("[FLEET-SIM] 默认 TCP 服务端未处于运行就绪状态，跳过虚拟车队自动连接");
                return;
            }
            int port = defaultServer.getActualPort();
            log.info("[FLEET-SIM] 检测到非 local 模式且自动仿真开启，正在连接虚拟车队至 127.0.0.1:{}", port);

            startSimulatedVehicle(DEVICE_ALPHA_001, GROUP_ALPHA, port,
                    116.407412, 39.904231, 72.5, "ON", false);
            startSimulatedVehicle(DEVICE_BETA_002, GROUP_BETA, port,
                    121.473701, 31.230416, 65.0, "ON", true);

            initialized.set(true);
            log.info("[FLEET-SIM] 虚拟车队初始化完毕: [{}, {}] 已上线并完成车队分组与初始遥测上报",
                    DEVICE_ALPHA_001, DEVICE_BETA_002);
        } catch (Exception ex) {
            stopAllSimulators();
            throw new IllegalStateException("初始化虚拟车联网终端失败: " + ex.getMessage(), ex);
        } finally {
            lifecycleLock.unlock();
        }
    }

    private void startSimulatedVehicle(String deviceId,
                                       String group,
                                       int port,
                                       double longitude,
                                       double latitude,
                                       double speed,
                                       String engineStatus,
                                       boolean useJt808Binary) {
        VehicleSimulator simulator = new VehicleSimulator(deviceId, "127.0.0.1", port, false);
        simulator.start();
        simulators.put(deviceId, simulator);
        simulator.login();

        awaitDeviceBound(deviceId);
        scenarioService.assignFleetGroup(deviceId, group);

        long beforeTimestamp = existingUpdateTime(deviceId);
        long seq = seqGenerator.incrementAndGet();
        if (useJt808Binary) {
            simulator.reportJt808Location(seq, longitude, latitude, speed, engineStatus);
        } else {
            LocationReportPacket packet = new LocationReportPacket(
                    deviceId, seq, longitude, latitude, speed, engineStatus, System.currentTimeMillis());
            simulator.reportLocation(packet);
        }
        awaitTelemetryUpdated(deviceId, beforeTimestamp, speed);
    }

    /**
     * 通过受控虚拟终端向服务端发送一帧真实线上行遥测报文（支持 JSON 或 JT/T 808 二进制帧）
     *
     * @param deviceId       已在线的仿真设备编号
     * @param protocol       协议格式（JSON 或 JT808）
     * @param longitude      经度
     * @param latitude       纬度
     * @param speed          车速
     * @param engineStatus   发动机状态
     * @return 本次上报流水号
     */
    public long emitSimulatedTelemetry(String deviceId,
                                       String protocol,
                                       double longitude,
                                       double latitude,
                                       double speed,
                                       String engineStatus) {
        VehicleSimulator simulator = simulators.get(deviceId);
        if (simulator == null || !simulator.isConnected()) {
            throw new BusinessException(CommonResultCode.NOT_FOUND,
                    "虚拟终端未启动或已离线，无法触发仿真上报: deviceId=" + deviceId);
        }
        long beforeTimestamp = existingUpdateTime(deviceId);
        long seq = seqGenerator.incrementAndGet();
        if ("JT808".equalsIgnoreCase(protocol)) {
            simulator.reportJt808Location(seq, longitude, latitude, speed, engineStatus);
        } else {
            simulator.reportLocation(new LocationReportPacket(
                    deviceId, seq, longitude, latitude, speed, engineStatus, System.currentTimeMillis()));
        }
        awaitTelemetryUpdated(deviceId, beforeTimestamp, speed);
        return seq;
    }

    /**
     * 判断指定设备是否受当前虚拟仿真器托管且在线
     *
     * @param deviceId 设备唯一编号
     * @return 是否托管在线
     */
    public boolean isManagedOnline(String deviceId) {
        VehicleSimulator simulator = simulators.get(deviceId);
        return simulator != null && simulator.isConnected();
    }

    /**
     * 获取当前托管的虚拟车机设备 ID 只读集合
     *
     * @return 虚拟设备 ID 集合
     */
    public Set<String> getSimulatedDeviceIds() {
        return Collections.unmodifiableSet(simulators.keySet());
    }

    private long existingUpdateTime(String deviceId) {
        var vo = scenarioService.getVehicleStatus(deviceId);
        return vo != null && vo.getUpdateTime() != null ? vo.getUpdateTime() : 0L;
    }

    private void awaitDeviceBound(String deviceId) {
        long deadline = System.currentTimeMillis() + WAIT_LOGIN_TIMEOUT_MS;
        while (System.currentTimeMillis() < deadline) {
            if (tcpServerTemplate.getSessionManager().getSessionByDeviceId(deviceId) != null) {
                return;
            }
            sleepBriefly();
        }
        throw new IllegalStateException("虚拟设备登录绑定超时: deviceId=" + deviceId);
    }

    private void awaitTelemetryUpdated(String deviceId, long beforeTimestamp, double expectedSpeed) {
        long deadline = System.currentTimeMillis() + WAIT_LOGIN_TIMEOUT_MS;
        while (System.currentTimeMillis() < deadline) {
            var status = scenarioService.getVehicleStatus(deviceId);
            if (status != null && status.getLastSpeed() != null && status.getUpdateTime() != null) {
                boolean speedMatched = Math.abs(status.getLastSpeed() - expectedSpeed) < 0.01;
                if (status.getUpdateTime() > beforeTimestamp && speedMatched) {
                    return;
                }
            }
            sleepBriefly();
        }
        log.debug("[FLEET-SIM] 等待设备 {} 遥测刷新超时（expectedSpeed={}），继续执行", deviceId, expectedSpeed);
    }

    private void sleepBriefly() {
        try {
            Thread.sleep(20L);
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("等待虚拟终端上线期间线程被中断", ex);
        }
    }

    private void stopAllSimulators() {
        for (Map.Entry<String, VehicleSimulator> entry : simulators.entrySet()) {
            try {
                entry.getValue().stop();
            } catch (Exception ex) {
                log.warn("[FLEET-SIM] 关闭虚拟车机 [{}] 时出现非致命异常: {}", entry.getKey(), ex.getMessage());
            }
        }
        simulators.clear();
        initialized.set(false);
    }

    @Override
    public void destroy() {
        lifecycleLock.lock();
        try {
            stopAllSimulators();
        } finally {
            lifecycleLock.unlock();
        }
    }
}
