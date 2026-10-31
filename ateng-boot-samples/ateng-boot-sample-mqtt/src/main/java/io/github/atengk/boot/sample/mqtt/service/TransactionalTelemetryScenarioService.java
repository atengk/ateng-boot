package io.github.atengk.boot.sample.mqtt.service;

import io.github.atengk.boot.mqtt.annotation.MqttListener;
import io.github.atengk.boot.mqtt.annotation.MqttVariable;
import io.github.atengk.boot.mqtt.template.MqttTemplate;
import io.github.atengk.boot.sample.mqtt.model.DeviceTelemetry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.Collections;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 场景业务服务 7：本地数据库事务感知协同与发信安全防护
 *
 * <p>演示特性：
 * <ul>
 *   <li>本地事务提交后发信（{@code sendAfterCommit}）：事务成功 commit 后触发投递，防脏读与反向竞态</li>
 *   <li>本地事务回滚安全丢弃：事务异常回滚时，已挂载的投递钩子被安全取消，Broker 零收到</li>
 *   <li>无事务平滑降级：无活跃事务时，自动降级为立即安全发布</li>
 * </ul>
 * </p>
 *
 * @author Ateng
 * @since 2026-10-31
 */
@Service
public class TransactionalTelemetryScenarioService {

    private static final Logger log = LoggerFactory.getLogger(TransactionalTelemetryScenarioService.class);

    private final MqttTemplate mqttTemplate;
    private final PlatformTransactionManager transactionManager;

    private final AtomicInteger dbRecordsCount = new AtomicInteger(0);
    private final List<DeviceTelemetry> receivedTxTelemetries = new CopyOnWriteArrayList<>();

    public TransactionalTelemetryScenarioService(MqttTemplate mqttTemplate,
                                                 PlatformTransactionManager transactionManager) {
        this.mqttTemplate = mqttTemplate;
        this.transactionManager = transactionManager;
    }

    /**
     * 本地事务成功提交后发信
     */
    public void recordTelemetryWithCommit(String deviceId, double temp, double humidity) {
        TransactionTemplate template = new TransactionTemplate(transactionManager);
        template.execute(status -> {
            // 1. 本地数据库写操作
            int currentId = dbRecordsCount.incrementAndGet();
            log.info("[SCENARIO-TX] Local DB transaction committed record [{}]: deviceId={}", currentId, deviceId);

            // 2. 挂载本地事务提交后发信钩子
            DeviceTelemetry telemetry = new DeviceTelemetry(deviceId, temp, humidity, System.currentTimeMillis());
            mqttTemplate.sendAfterCommit("iot/devices/" + deviceId + "/tx", telemetry, 1);
            return null;
        });
    }

    /**
     * 本地事务回滚，投递钩子被安全丢弃
     */
    public void recordTelemetryWithRollback(String deviceId, double temp, double humidity) {
        TransactionTemplate template = new TransactionTemplate(transactionManager);
        try {
            template.execute(status -> {
                // 1. 本地数据库写操作
                dbRecordsCount.incrementAndGet();
                log.info("[SCENARIO-TX] Local DB transaction recording (will rollback): deviceId={}", deviceId);

                // 2. 挂载本地事务提交后发信钩子
                DeviceTelemetry telemetry = new DeviceTelemetry(deviceId, temp, humidity, System.currentTimeMillis());
                mqttTemplate.sendAfterCommit("iot/devices/" + deviceId + "/tx", telemetry, 1);

                // 3. 模拟业务异常，触发事务回滚
                throw new IllegalStateException("Simulated business error to trigger rollback");
            });
        } catch (IllegalStateException e) {
            log.warn("[SCENARIO-TX] Handled simulated transaction rollback: {}", e.getMessage());
        }
    }

    /**
     * 事务外直接调用，触发平滑降级（立即投递）
     */
    public void recordTelemetryWithoutTx(String deviceId, double temp, double humidity) {
        log.info("[SCENARIO-TX] Recording telemetry outside transaction for device [{}]", deviceId);
        DeviceTelemetry telemetry = new DeviceTelemetry(deviceId, temp, humidity, System.currentTimeMillis());
        mqttTemplate.sendAfterCommit("iot/devices/" + deviceId + "/tx", telemetry, 1);
    }

    @MqttListener(topics = "iot/devices/{deviceId}/tx", qos = 1)
    public void onTxTelemetry(DeviceTelemetry telemetry, @MqttVariable("deviceId") String deviceId) {
        log.info("[SCENARIO-TX] Received transaction telemetry: deviceId={}, temp={}, humidity={}",
                deviceId, telemetry.getTemperature(), telemetry.getHumidity());
        receivedTxTelemetries.add(telemetry);
    }

    public int getDbRecordsCount() {
        return dbRecordsCount.get();
    }

    public List<DeviceTelemetry> getReceivedTxTelemetries() {
        return Collections.unmodifiableList(receivedTxTelemetries);
    }

    public void clear() {
        dbRecordsCount.set(0);
        receivedTxTelemetries.clear();
    }
}
