package io.github.atengk.boot.mqtt.defense;

import io.github.atengk.boot.core.code.CommonResultCode;
import io.github.atengk.boot.core.exception.BusinessException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.util.unit.DataSize;

/**
 * MQTT 报文载荷安全守卫
 * 基于配置的 maxPayloadSize 强类型尺寸阈值，在出入站消息处理前对大报文实施快速熔断拦截，防御 JVM 堆内存 OOM
 *
 * @author Ateng
 * @since 2026-10-23
 */
public class PayloadGuard {

    private static final Logger log = LoggerFactory.getLogger(PayloadGuard.class);

    /**
     * 校验载荷尺寸是否超过上限
     *
     * @param payload        载荷字节数组
     * @param maxPayloadSize 最大载荷尺寸限制
     * @param topic          目标或来源主题 (用于日志与异常排障)
     * @throws BusinessException 当载荷尺寸超限时抛出
     */
    public void validate(byte[] payload, DataSize maxPayloadSize, String topic) {
        if (payload == null || maxPayloadSize == null) {
            return;
        }
        long maxBytes = maxPayloadSize.toBytes();
        if (maxBytes > 0 && payload.length > maxBytes) {
            String errorMsg = String.format(
                    "MQTT message payload size [%d bytes] on topic [%s] exceeds configured maxPayloadSize limit [%d bytes] (%s)",
                    payload.length, topic, maxBytes, maxPayloadSize
            );
            log.error("[PayloadGuard] {}", errorMsg);
            throw new BusinessException(CommonResultCode.BAD_REQUEST, errorMsg);
        }
    }
}
