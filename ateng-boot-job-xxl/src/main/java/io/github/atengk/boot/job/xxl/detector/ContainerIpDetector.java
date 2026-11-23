package io.github.atengk.boot.job.xxl.detector;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.util.StringUtils;

import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.NetworkInterface;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Enumeration;
import java.util.List;
import java.util.Locale;
import java.util.function.Function;

/**
 * 容器网络与网卡 IP 智能嗅探器
 *
 * <p>优先按优先级探测 Kubernetes/容器环境中的有效可路由 IP 地址：
 * 显式配置 -> K8s POD_IP 环境变量 -> K8s HOST_IP 环境变量 -> 物理真实网卡 IPv4 局域网地址 -> 回退本地默认地址。
 * 主动防御并过滤掉 Docker、Flannel、Calico 等无路由价值的虚拟容器网桥地址，防御执行器向调度中心注册不可达 IP。</p>
 *
 * @author Ateng
 * @since 2026-10-31
 */
public final class ContainerIpDetector {

    private static final Logger log = LoggerFactory.getLogger(ContainerIpDetector.class);

    private static final List<String> VIRTUAL_NIC_PREFIXES = List.of(
            "docker", "flannel", "cni", "veth", "virbr", "tun", "tap", "cali", "kube", "br-"
    );

    private ContainerIpDetector() {
    }

    /**
     * 智能嗅探执行器注册与监听 IP
     *
     * @param configuredIp 开发者显式配置的 IP (若非空则直接采纳)
     * @return 探测到的最优 IPv4 地址
     */
    public static String detectIp(String configuredIp) {
        return detectIp(configuredIp, System::getenv);
    }

    /**
     * 智能嗅探执行器注册与监听 IP（支持注入环境变量提供器，便于测试隔离）
     *
     * @param configuredIp 开发者显式配置的 IP
     * @param envGetter    环境变量读取函数
     * @return 探测到的最优 IPv4 地址
     */
    public static String detectIp(String configuredIp, Function<String, String> envGetter) {
        // 1. 显式配置优先
        if (StringUtils.hasText(configuredIp)) {
            String trimmed = configuredIp.trim();
            log.info("XXL-Job 执行器采用显式配置 IP: [{}]", trimmed);
            return trimmed;
        }

        // 2. 优先尝试探测 Kubernetes POD_IP
        String podIp = envGetter.apply("POD_IP");
        if (isValidRoutableIp(podIp)) {
            log.info("XXL-Job 执行器自动嗅探到 Kubernetes POD_IP: [{}]", podIp);
            return podIp;
        }

        // 3. 其次尝试探测 Kubernetes HOST_IP
        String hostIp = envGetter.apply("HOST_IP");
        if (isValidRoutableIp(hostIp)) {
            log.info("XXL-Job 执行器自动嗅探到 Kubernetes HOST_IP: [{}]", hostIp);
            return hostIp;
        }

        // 4. 遍历物理真实网卡
        String nicIp = findFirstRoutableInterfaceIp();
        if (StringUtils.hasText(nicIp)) {
            log.info("XXL-Job 执行器自动探测到物理真实网卡 IP: [{}]", nicIp);
            return nicIp;
        }

        // 5. 保底降级
        try {
            String fallbackIp = InetAddress.getLocalHost().getHostAddress();
            log.warn("XXL-Job 未能探测到容器或内网网卡 IP，降级使用本机 LocalHost IP: [{}]", fallbackIp);
            return fallbackIp;
        } catch (Exception e) {
            log.warn("XXL-Job 解析本机 LocalHost 失败，使用最终保底地址 [127.0.0.1]", e);
            return "127.0.0.1";
        }
    }

    /**
     * 遍历本机有效网络接口，筛选首选 IPv4 站点本地地址
     */
    private static String findFirstRoutableInterfaceIp() {
        try {
            Enumeration<NetworkInterface> interfaces = NetworkInterface.getNetworkInterfaces();
            if (interfaces == null) {
                return null;
            }

            List<Inet4Address> siteLocalCandidates = new ArrayList<>();
            List<Inet4Address> otherCandidates = new ArrayList<>();

            for (NetworkInterface nic : Collections.list(interfaces)) {
                if (!nic.isUp() || nic.isLoopback() || nic.isVirtual()) {
                    continue;
                }
                if (isVirtualOrContainerInterface(nic.getName()) || isVirtualOrContainerInterface(nic.getDisplayName())) {
                    continue;
                }

                Enumeration<InetAddress> addresses = nic.getInetAddresses();
                while (addresses.hasMoreElements()) {
                    InetAddress address = addresses.nextElement();
                    if (address instanceof Inet4Address ipv4 && !ipv4.isLoopbackAddress() && !ipv4.isAnyLocalAddress()) {
                        if (ipv4.isSiteLocalAddress()) {
                            siteLocalCandidates.add(ipv4);
                        } else {
                            otherCandidates.add(ipv4);
                        }
                    }
                }
            }

            // 优先选择内网私有地址 (如 192.168.x.x, 10.x.x.x, 172.16-31.x.x)
            if (!siteLocalCandidates.isEmpty()) {
                return siteLocalCandidates.get(0).getHostAddress();
            }
            if (!otherCandidates.isEmpty()) {
                return otherCandidates.get(0).getHostAddress();
            }
        } catch (Exception e) {
            log.error("遍历网络接口探测 IP 发生异常", e);
        }
        return null;
    }

    /**
     * 判断网卡名称是否属于虚拟网卡、容器网桥或不可路由虚拟设备
     *
     * @param name 网卡名称或显示名称
     * @return 若命中虚拟设备特征返回 true
     */
    public static boolean isVirtualOrContainerInterface(String name) {
        if (!StringUtils.hasText(name)) {
            return false;
        }
        String lower = name.toLowerCase(Locale.ROOT);
        for (String prefix : VIRTUAL_NIC_PREFIXES) {
            if (lower.contains(prefix)) {
                return true;
            }
        }
        return false;
    }

    /**
     * 判断给定 IP 字符串是否为非空且非回环的合法 IP
     */
    private static boolean isValidRoutableIp(String ip) {
        if (!StringUtils.hasText(ip)) {
            return false;
        }
        String trimmed = ip.trim();
        if ("127.0.0.1".equals(trimmed) || "localhost".equalsIgnoreCase(trimmed)) {
            return false;
        }
        try {
            InetAddress address = InetAddress.getByName(trimmed);
            return !address.isLoopbackAddress() && address instanceof Inet4Address;
        } catch (Exception e) {
            return false;
        }
    }

}
