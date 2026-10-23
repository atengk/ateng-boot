package io.github.atengk.boot.mqtt.properties;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * ClientIdStrategy 单元测试
 *
 * @author Ateng
 * @since 2026-10-23
 */
class ClientIdStrategyTest {

    @Test
    @DisplayName("测试 STATIC 策略：保持基础标识原样不变")
    void testStaticStrategy() {
        String baseId = "iot-gateway";
        String resolved = ClientIdStrategy.STATIC.resolve(baseId);
        assertEquals(baseId, resolved);
    }

    @Test
    @DisplayName("测试 RANDOM_SUFFIX 策略：追加随机短序列且连续两次不同")
    void testRandomSuffixStrategy() {
        String baseId = "iot-gateway";
        String id1 = ClientIdStrategy.RANDOM_SUFFIX.resolve(baseId);
        String id2 = ClientIdStrategy.RANDOM_SUFFIX.resolve(baseId);

        assertTrue(id1.startsWith(baseId + "_"));
        assertTrue(id2.startsWith(baseId + "_"));
        assertNotEquals(id1, id2, "两次随机生成的 ClientId 必须不同以防互踢");
        assertEquals(baseId.length() + 7, id1.length(), "基础长度加下划线加6位短序列");
    }

    @Test
    @DisplayName("测试 IP_PID 策略：包含 IP 与 PID")
    void testIpPidStrategy() {
        String baseId = "iot-gateway";
        String resolved = ClientIdStrategy.IP_PID.resolve(baseId);

        assertTrue(resolved.startsWith(baseId + "_"));
        String pid = String.valueOf(ProcessHandle.current().pid());
        assertTrue(resolved.endsWith(pid));
    }
}
