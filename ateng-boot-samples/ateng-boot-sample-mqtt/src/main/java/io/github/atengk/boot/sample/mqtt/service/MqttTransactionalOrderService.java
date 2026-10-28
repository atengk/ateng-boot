package io.github.atengk.boot.sample.mqtt.service;

import io.github.atengk.boot.mqtt.template.MqttTemplate;
import io.github.atengk.boot.sample.mqtt.model.DeviceTelemetry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.concurrent.atomic.AtomicInteger;

/**
 * MQTT 本地事务感知协同业务服务
 * 演示事务提交后安全投递与平滑降级
 *
 * @author Ateng
 * @since 2026-10-23
 */
@Service
public class MqttTransactionalOrderService {

    private static final Logger log = LoggerFactory.getLogger(MqttTransactionalOrderService.class);

    private final MqttTemplate mqttTemplate;
    private final AtomicInteger databaseRecords = new AtomicInteger(0);

    public MqttTransactionalOrderService(MqttTemplate mqttTemplate) {
        this.mqttTemplate = mqttTemplate;
    }

    /**
     * 执行业务变更并结合 sendAfterCommit 保证事务提交后才投递报文
     *
     * @param deviceId 设备标识
     * @param temp     温度
     * @param humidity 湿度
     */
    public void recordTelemetryWithTx(String deviceId, double temp, double humidity) {
        // 1. 模拟本地数据库事务持久化
        int recordId = databaseRecords.incrementAndGet();
        log.info("[MQTT-Sample] Saved telemetry record [{}] into DB for device [{}]", recordId, deviceId);

        // 2. 本地事务安全发信（只有事务成功 commit 才向 Broker 发信，防脏读与反向竞态）
        DeviceTelemetry telemetry = new DeviceTelemetry(deviceId, temp, humidity, System.currentTimeMillis());
        mqttTemplate.sendAfterCommit("iot/devices/" + deviceId + "/telemetry", telemetry, 1);
        log.info("[MQTT-Sample] Registered sendAfterCommit for telemetry of device [{}]", deviceId);
    }

    public int getDatabaseRecordsCount() {
        return databaseRecords.get();
    }
}
