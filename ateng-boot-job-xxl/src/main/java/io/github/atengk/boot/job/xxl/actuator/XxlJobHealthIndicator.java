package io.github.atengk.boot.job.xxl.actuator;

import com.xxl.job.core.executor.impl.XxlJobSpringExecutor;
import io.github.atengk.boot.job.xxl.properties.XxlJobProperties;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.actuate.health.Health;
import org.springframework.boot.actuate.health.HealthIndicator;
import org.springframework.util.StringUtils;

import java.net.InetSocketAddress;
import java.net.Socket;

/**
 * XXL-Job 分布式任务调度执行器健康探针
 *
 * <p>暴露于 Spring Boot Actuator {@code /actuator/health} 端点下，动态探测执行器运行时健康状态：
 * <ul>
 *     <li><b>开闭状态探测</b>：执行器未激活时报告 {@code DISABLED}，未配置调度地址时报告 {@code BYPASSED}；</li>
 *     <li><b>Netty 监听探测</b>：探测执行器内嵌 Netty 服务端端口真实监听与网络可达状态；</li>
 *     <li><b>执行器元数据暴露</b>：聚合应用名称 (appname)、端口 (port)、绑定 IP/地址与调度中心地址列表。</li>
 * </ul></p>
 *
 * @author Ateng
 * @since 2026-10-31
 */
public class XxlJobHealthIndicator implements HealthIndicator {

    private final XxlJobProperties properties;
    private final ObjectProvider<XxlJobSpringExecutor> executorProvider;

    public XxlJobHealthIndicator(XxlJobProperties properties, ObjectProvider<XxlJobSpringExecutor> executorProvider) {
        this.properties = properties != null ? properties : new XxlJobProperties();
        this.executorProvider = executorProvider;
    }

    @Override
    public Health health() {
        if (!properties.isEnabled()) {
            return Health.up()
                    .withDetail("status", "DISABLED")
                    .withDetail("message", "XXL-Job 执行器已显式禁用")
                    .build();
        }

        if (!StringUtils.hasText(properties.getAdminAddresses())) {
            return Health.unknown()
                    .withDetail("status", "BYPASSED")
                    .withDetail("message", "未配置调度中心地址 ('ateng.boot.job.xxl.admin-addresses')，处于静默旁路模式")
                    .build();
        }

        XxlJobSpringExecutor executor = executorProvider != null ? executorProvider.getIfAvailable() : null;
        if (executor == null) {
            return Health.down()
                    .withDetail("status", "NOT_INITIALIZED")
                    .withDetail("message", "XXL-Job 执行器实例未完成初始化装配")
                    .build();
        }

        int port = properties.getPort();
        boolean portListening = isPortListening("127.0.0.1", port);

        Health.Builder builder = portListening ? Health.up() : Health.down();
        return builder
                .withDetail("appname", properties.getAppname())
                .withDetail("port", port)
                .withDetail("portListening", portListening)
                .withDetail("ip", StringUtils.hasText(properties.getIp()) ? properties.getIp() : "auto-detected")
                .withDetail("address", StringUtils.hasText(properties.getAddress()) ? properties.getAddress() : "auto-registered")
                .withDetail("adminAddresses", properties.getAdminAddresses())
                .withDetail("logPath", properties.getLogPath())
                .withDetail("managedPoolEnabled", properties.isManagedPoolEnabled())
                .withDetail("alertEnabled", properties.isAlertEnabled())
                .build();
    }

    /**
     * 探测指定 IP 与端口是否处于可用监听状态
     */
    private boolean isPortListening(String host, int port) {
        if (port <= 0 || port > 65535) {
            return false;
        }
        try (Socket socket = new Socket()) {
            socket.connect(new InetSocketAddress(host, port), 300);
            return true;
        } catch (Exception e) {
            return false;
        }
    }
}
