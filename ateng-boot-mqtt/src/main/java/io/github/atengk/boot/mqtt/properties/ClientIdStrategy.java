package io.github.atengk.boot.mqtt.properties;

import io.github.atengk.boot.core.util.IdUtil;

import java.net.InetAddress;
import java.util.Locale;

/**
 * MQTT 客户端标识符生成与防互踢策略
 *
 * @author Ateng
 * @since 2026-10-23
 */
public enum ClientIdStrategy {

    /**
     * 静态模式：严格使用配置的 clientId，不做任何动态修饰（用于离线消息追溯的特定实例）
     */
    STATIC {
        @Override
        public String resolve(String baseClientId) {
            return baseClientId;
        }
    },

    /**
     * 随机后缀模式（默认推荐）：自动追加 6 位短随机序列，彻底防御多 Pod 并发启动互相踢下线
     */
    RANDOM_SUFFIX {
        @Override
        public String resolve(String baseClientId) {
            String shortId = IdUtil.fastSimpleUUID().substring(0, 6).toLowerCase(Locale.ROOT);
            return baseClientId + "_" + shortId;
        }
    },

    /**
     * 主机与进程绑定模式：附加当前宿主机 IP 与 JVM 进程 PID
     */
    IP_PID {
        @Override
        public String resolve(String baseClientId) {
            String ip = getLocalHostAddress();
            String pid = String.valueOf(ProcessHandle.current().pid());
            return baseClientId + "_" + ip + "_" + pid;
        }
    };

    /**
     * 根据基础标识符解析最终用于连接的 ClientId
     *
     * @param baseClientId 基础配置标识
     * @return 实际 ClientId
     */
    public abstract String resolve(String baseClientId);

    private static String getLocalHostAddress() {
        try {
            return InetAddress.getLocalHost().getHostAddress().replace('.', '_');
        } catch (Throwable t) {
            return "127_0_0_1";
        }
    }
}
