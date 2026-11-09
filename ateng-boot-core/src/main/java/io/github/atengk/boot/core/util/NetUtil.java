package io.github.atengk.boot.core.util;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.net.DatagramSocket;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.NetworkInterface;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Enumeration;
import java.util.List;
import java.util.concurrent.locks.ReentrantLock;
import java.util.regex.Pattern;

/**
 * 网络与通信地址通用工具类
 * 提供多网卡内网 IPv4 智能识别解析、内核路由嗅探、虚拟网卡全景过滤、本地空闲端口安全探测与远程服务连通性探针
 *
 * @author Ateng
 * @since 2026-10-26
 */
public final class NetUtil {

    private static final Logger log = LoggerFactory.getLogger(NetUtil.class);

    public static final String LOCALHOST_IPV4 = "127.0.0.1";
    public static final int MIN_PORT = 1024;
    public static final int MAX_PORT = 65535;

    public static final String PROP_PREFERRED_IP = "ateng.boot.core.net.preferred-ip";
    public static final String PROP_PREFERRED_NETWORKS = "ateng.boot.core.net.preferred-networks";
    public static final String PROP_IGNORED_INTERFACES = "ateng.boot.core.net.ignored-interfaces";

    public static final String ENV_PREFERRED_IP = "ATENG_BOOT_CORE_NET_PREFERRED_IP";
    public static final String ENV_PREFERRED_NETWORKS = "ATENG_BOOT_CORE_NET_PREFERRED_NETWORKS";
    public static final String ENV_IGNORED_INTERFACES = "ATENG_BOOT_CORE_NET_IGNORED_INTERFACES";

    /**
     * 常见虚拟网卡、容器、VPN 与隧道接口黑名单名称模式
     * 覆盖 VirtualBox、VMware、Hyper-V、WSL、Docker、K8s CNI、Tailscale、ZeroTier、Sangfor 等及中文“虚拟”
     */
    private static final Pattern VIRTUAL_INTERFACE_PATTERN = Pattern.compile(
            "(?i).*(docker|veth|vmnet|virbr|vboxnet|vbox|virtualbox|vmware|hyper-v|wsl|dummy|tailscale|zerotier|wireguard|openvpn|tap|tun|vpn|sangfor|atrust|clash|npcap|虚拟).*"
    );

    private static volatile String cachedLocalIpv4;

    private static volatile String preferredIpConfig;
    private static volatile List<String> preferredNetworksConfig;
    private static volatile List<String> ignoredInterfacesConfig;

    private static final ReentrantLock LOCK = new ReentrantLock();

    private NetUtil() {
    }

    /**
     * 显式配置网络探测规则（通常由框架环境后处理器自动调用，亦支持单测或非 Spring 场景调用）
     *
     * @param preferredIp       首选固定 IP（可为 null）
     * @param preferredNetworks 首选网段列表（可为 null）
     * @param ignoredInterfaces 自定义排除网卡列表（可为 null）
     */
    public static void configure(String preferredIp, List<String> preferredNetworks, List<String> ignoredInterfaces) {
        LOCK.lock();
        try {
            preferredIpConfig = (preferredIp != null && !preferredIp.isBlank()) ? preferredIp.trim() : null;
            preferredNetworksConfig = (preferredNetworks != null && !preferredNetworks.isEmpty()) ? new ArrayList<>(preferredNetworks) : null;
            ignoredInterfacesConfig = (ignoredInterfaces != null && !ignoredInterfaces.isEmpty()) ? new ArrayList<>(ignoredInterfaces) : null;
            clearLocalIpv4Cache();
        } finally {
            if (LOCK.isHeldByCurrentThread()) {
                LOCK.unlock();
            }
        }
    }

    /**
     * 重置显式配置与缓存（恢复为基于系统属性、环境变量与自动嗅探决策）
     */
    public static void resetConfiguration() {
        LOCK.lock();
        try {
            preferredIpConfig = null;
            preferredNetworksConfig = null;
            ignoredInterfacesConfig = null;
            clearLocalIpv4Cache();
        } finally {
            if (LOCK.isHeldByCurrentThread()) {
                LOCK.unlock();
            }
        }
    }

    /**
     * 清理本机 IPv4 地址缓存（主要用于测试隔离或网络拓扑动态刷新）
     */
    public static void clearLocalIpv4Cache() {
        LOCK.lock();
        try {
            cachedLocalIpv4 = null;
        } finally {
            if (LOCK.isHeldByCurrentThread()) {
                LOCK.unlock();
            }
        }
    }

    /**
     * 获取当前生效的首选固定 IP
     *
     * @return 首选固定 IP 或 null
     */
    public static String getEffectivePreferredIp() {
        if (preferredIpConfig != null && !preferredIpConfig.isBlank()) {
            return preferredIpConfig;
        }
        String sysProp = System.getProperty(PROP_PREFERRED_IP);
        if (sysProp != null && !sysProp.isBlank()) {
            return sysProp.trim();
        }
        String env = System.getenv(ENV_PREFERRED_IP);
        if (env != null && !env.isBlank()) {
            return env.trim();
        }
        return null;
    }

    /**
     * 获取当前生效的首选网段列表
     *
     * @return 首选网段列表（非 null，无配置时返回空列表）
     */
    public static List<String> getEffectivePreferredNetworks() {
        if (preferredNetworksConfig != null && !preferredNetworksConfig.isEmpty()) {
            return Collections.unmodifiableList(preferredNetworksConfig);
        }
        String sysProp = System.getProperty(PROP_PREFERRED_NETWORKS);
        if (sysProp != null && !sysProp.isBlank()) {
            return parseCommaList(sysProp);
        }
        String env = System.getenv(ENV_PREFERRED_NETWORKS);
        if (env != null && !env.isBlank()) {
            return parseCommaList(env);
        }
        return Collections.emptyList();
    }

    /**
     * 获取当前生效的排除网卡列表
     *
     * @return 排除网卡列表（非 null，无配置时返回空列表）
     */
    public static List<String> getEffectiveIgnoredInterfaces() {
        if (ignoredInterfacesConfig != null && !ignoredInterfacesConfig.isEmpty()) {
            return Collections.unmodifiableList(ignoredInterfacesConfig);
        }
        String sysProp = System.getProperty(PROP_IGNORED_INTERFACES);
        if (sysProp != null && !sysProp.isBlank()) {
            return parseCommaList(sysProp);
        }
        String env = System.getenv(ENV_IGNORED_INTERFACES);
        if (env != null && !env.isBlank()) {
            return parseCommaList(env);
        }
        return Collections.emptyList();
    }

    /**
     * 判断指定网络接口是否为虚拟网卡或无物理介质网卡
     * 自动检测虚拟网卡标识、点对点链路以及 VirtualBox、VMware、Docker、WSL、VPN 等全景虚拟网卡模式
     *
     * @param ni 网络接口对象
     * @return true 表示为虚拟网卡或点对点网卡
     */
    public static boolean isVirtualInterface(NetworkInterface ni) {
        if (ni == null) {
            return false;
        }
        try {
            if (ni.isVirtual() || ni.isPointToPoint()) {
                return true;
            }
            String name = ni.getName();
            if (name != null && VIRTUAL_INTERFACE_PATTERN.matcher(name).matches()) {
                return true;
            }
            String displayName = ni.getDisplayName();
            if (displayName != null && VIRTUAL_INTERFACE_PATTERN.matcher(displayName).matches()) {
                return true;
            }
        } catch (Throwable ex) {
            log.trace("探测网络接口状态异常: {}", ni, ex);
        }
        return false;
    }

    /**
     * 判断指定网络接口是否被自定义忽略规则排除
     *
     * @param ni 网络接口对象
     * @return true 表示被排除
     */
    public static boolean isIgnoredInterface(NetworkInterface ni) {
        return isIgnoredInterface(ni, getEffectiveIgnoredInterfaces());
    }

    /**
     * 判断指定网络接口是否匹配排除规则列表
     *
     * @param ni              网络接口对象
     * @param ignoredPatterns 排除规则列表（支持通配符与正则）
     * @return true 表示匹配排除
     */
    public static boolean isIgnoredInterface(NetworkInterface ni, List<String> ignoredPatterns) {
        if (ni == null || ignoredPatterns == null || ignoredPatterns.isEmpty()) {
            return false;
        }
        String name = ni.getName();
        String displayName = ni.getDisplayName();
        for (String pattern : ignoredPatterns) {
            if (pattern == null || pattern.isBlank()) {
                continue;
            }
            if (matchPattern(pattern, name) || matchPattern(pattern, displayName)) {
                return true;
            }
        }
        return false;
    }

    /**
     * 获取本机首选内网非回环 IPv4 地址
     * 遵循五级决策链：固定 IP -> 首选网段 -> OS 内核路由探测 -> 物理网卡全景黑名单枚举 -> 保底回退
     *
     * @return 本机内网 IPv4 地址；探测失败时安全回退返回 "127.0.0.1"
     */
    public static String getLocalIpv4() {
        if (cachedLocalIpv4 != null) {
            return cachedLocalIpv4;
        }

        LOCK.lock();
        try {
            if (cachedLocalIpv4 != null) {
                return cachedLocalIpv4;
            }

            try {
                String preferredIp = getEffectivePreferredIp();
                List<String> preferredNetworks = getEffectivePreferredNetworks();
                List<String> ignoredInterfaces = getEffectiveIgnoredInterfaces();

                // 1. 显式固定 IP（最高优先级逃生舱）
                if (preferredIp != null && isValidIpv4(preferredIp) && !isLoopbackIpv4(preferredIp)) {
                    cachedLocalIpv4 = preferredIp;
                    return cachedLocalIpv4;
                }

                Enumeration<NetworkInterface> interfaces = NetworkInterface.getNetworkInterfaces();

                // 2. 首选网段匹配（若配置了 preferred-networks）
                if (preferredNetworks != null && !preferredNetworks.isEmpty() && interfaces != null) {
                    while (interfaces.hasMoreElements()) {
                        NetworkInterface ni = interfaces.nextElement();
                        if (ni.isLoopback() || !ni.isUp() || isVirtualInterface(ni) || isIgnoredInterface(ni, ignoredInterfaces)) {
                            continue;
                        }

                        Enumeration<InetAddress> addresses = ni.getInetAddresses();
                        while (addresses.hasMoreElements()) {
                            InetAddress addr = addresses.nextElement();
                            if (addr instanceof Inet4Address && !addr.isLoopbackAddress()) {
                                String hostAddress = addr.getHostAddress();
                                if (matchesAnyPreferredNetwork(hostAddress, preferredNetworks)) {
                                    cachedLocalIpv4 = hostAddress;
                                    return cachedLocalIpv4;
                                }
                            }
                        }
                    }
                }

                // 3. 操作系统内核路由探测（UDP Socket Dummy Probe）
                InetAddress routedAddress = probeKernelRoutedAddress();
                if (routedAddress != null) {
                    NetworkInterface routedNi = NetworkInterface.getByInetAddress(routedAddress);
                    boolean isExcluded = routedNi != null && (routedNi.isLoopback() || !routedNi.isUp()
                            || isVirtualInterface(routedNi) || isIgnoredInterface(routedNi, ignoredInterfaces));
                    if (!isExcluded) {
                        cachedLocalIpv4 = routedAddress.getHostAddress();
                        return cachedLocalIpv4;
                    }
                }

                // 4. 遍历物理网卡全景黑名单过滤（优先 SiteLocal 私网地址）
                interfaces = NetworkInterface.getNetworkInterfaces();
                InetAddress candidate = null;

                if (interfaces != null) {
                    while (interfaces.hasMoreElements()) {
                        NetworkInterface ni = interfaces.nextElement();
                        if (ni.isLoopback() || !ni.isUp() || isVirtualInterface(ni) || isIgnoredInterface(ni, ignoredInterfaces)) {
                            continue;
                        }

                        Enumeration<InetAddress> addresses = ni.getInetAddresses();
                        while (addresses.hasMoreElements()) {
                            InetAddress addr = addresses.nextElement();
                            if (addr instanceof Inet4Address && !addr.isLoopbackAddress()) {
                                if (addr.isSiteLocalAddress()) {
                                    cachedLocalIpv4 = addr.getHostAddress();
                                    return cachedLocalIpv4;
                                }
                                if (candidate == null) {
                                    candidate = addr;
                                }
                            }
                        }
                    }
                }

                if (candidate != null) {
                    cachedLocalIpv4 = candidate.getHostAddress();
                    return cachedLocalIpv4;
                }

                // 5. 保底回退：尝试 localHost 或任意非回环 IPv4
                InetAddress localHost = InetAddress.getLocalHost();
                if (localHost instanceof Inet4Address && !localHost.isLoopbackAddress()) {
                    cachedLocalIpv4 = localHost.getHostAddress();
                    return cachedLocalIpv4;
                }
            } catch (Throwable ignored) {
                // 防御网络接口探测异常
            }

            cachedLocalIpv4 = LOCALHOST_IPV4;
            return cachedLocalIpv4;
        } finally {
            if (LOCK.isHeldByCurrentThread()) {
                LOCK.unlock();
            }
        }
    }

    /**
     * 探测 OS 内核路由表选定的最佳出口 IPv4 地址（UDP Dummy Connect）
     * 不发送任何网络数据包，0 网络 IO，离线或无网关时静默返回 null
     *
     * @return 路由出口 IPv4 地址，失败时返回 null
     */
    private static InetAddress probeKernelRoutedAddress() {
        InetAddress addr = doProbeKernelRoute("223.5.5.5");
        if (addr == null) {
            addr = doProbeKernelRoute("8.8.8.8");
        }
        return addr;
    }

    private static InetAddress doProbeKernelRoute(String targetHost) {
        try (DatagramSocket socket = new DatagramSocket()) {
            socket.connect(new InetSocketAddress(targetHost, 80));
            InetAddress localAddress = socket.getLocalAddress();
            if (localAddress instanceof Inet4Address && !localAddress.isLoopbackAddress() && !localAddress.isAnyLocalAddress()) {
                return localAddress;
            }
        } catch (Throwable ignored) {
            // 离线环境或无路由时静默回退
        }
        return null;
    }

    /**
     * 判断指定 IPv4 是否匹配首选网段规则列表中的任一规则
     *
     * @param ip                IPv4 字符串
     * @param preferredNetworks 网段规则列表（支持通配符与 CIDR）
     * @return true 表示匹配
     */
    public static boolean matchesAnyPreferredNetwork(String ip, List<String> preferredNetworks) {
        if (ip == null || preferredNetworks == null || preferredNetworks.isEmpty()) {
            return false;
        }
        for (String rule : preferredNetworks) {
            if (matchesPreferredNetwork(ip, rule)) {
                return true;
            }
        }
        return false;
    }

    /**
     * 判断指定 IPv4 是否匹配单个首选网段规则（支持 CIDR 掩码如 192.168.3.0/24 与通配符如 192.168.3.*）
     *
     * @param ip   IPv4 字符串
     * @param rule 网段规则
     * @return true 表示匹配
     */
    public static boolean matchesPreferredNetwork(String ip, String rule) {
        if (ip == null || rule == null) {
            return false;
        }
        ip = ip.trim();
        rule = rule.trim();
        if (rule.contains("/")) {
            return matchCidr(ip, rule);
        }
        if (rule.contains("*") || rule.contains("?")) {
            return matchWildcard(ip, rule);
        }
        return ip.equalsIgnoreCase(rule);
    }

    /**
     * 校验 IPv4 是否处于 CIDR 子网内（如 192.168.3.41 与 192.168.3.0/24）
     *
     * @param ipStr IPv4 字符串
     * @param cidr  CIDR 掩码格式字符串
     * @return true 表示在该 CIDR 子网内
     */
    public static boolean matchCidr(String ipStr, String cidr) {
        try {
            int slashIndex = cidr.indexOf('/');
            if (slashIndex < 0) {
                return false;
            }
            String netStr = cidr.substring(0, slashIndex).trim();
            int prefixLen = Integer.parseInt(cidr.substring(slashIndex + 1).trim());
            if (prefixLen < 0 || prefixLen > 32) {
                return false;
            }
            int ip = ipToInt(InetAddress.getByName(ipStr).getAddress());
            int net = ipToInt(InetAddress.getByName(netStr).getAddress());
            int mask = prefixLen == 0 ? 0 : 0xFFFFFFFF << (32 - prefixLen);
            return (ip & mask) == (net & mask);
        } catch (Throwable ignored) {
            return false;
        }
    }

    private static int ipToInt(byte[] bytes) {
        int result = 0;
        for (byte b : bytes) {
            result = (result << 8) | (b & 0xFF);
        }
        return result;
    }

    /**
     * 通用文本模式匹配（支持精确匹配、正则表达式与标准通配符 * ?）
     *
     * @param pattern 模式字符串
     * @param text    待匹配文本
     * @return true 表示匹配成功
     */
    public static boolean matchPattern(String pattern, String text) {
        if (pattern == null || text == null) {
            return false;
        }
        pattern = pattern.trim();
        text = text.trim();
        if (pattern.equalsIgnoreCase(text)) {
            return true;
        }
        try {
            if (text.matches("(?i)" + pattern)) {
                return true;
            }
        } catch (Throwable ignored) {
        }
        return matchWildcard(text, pattern);
    }

    /**
     * 通配符模式匹配（将 * 转为 .*，将 ? 转为 .）
     *
     * @param text    待匹配文本
     * @param pattern 通配符模式
     * @return true 表示匹配成功
     */
    public static boolean matchWildcard(String text, String pattern) {
        if (text == null || pattern == null) {
            return false;
        }
        if (pattern.contains("*") || pattern.contains("?")) {
            String regex = "\\Q" + pattern.replace("\\E", "\\E\\\\E\\Q") + "\\E";
            regex = regex.replace("*", "\\E.*\\Q").replace("?", "\\E.\\Q");
            return text.matches("(?i)" + regex);
        }
        return text.equalsIgnoreCase(pattern);
    }

    /**
     * 校验字符串是否为合法的点分十进制 IPv4 地址
     *
     * @param ip IP 字符串
     * @return true 表示合法 IPv4
     */
    public static boolean isValidIpv4(String ip) {
        if (ip == null || ip.isBlank()) {
            return false;
        }
        String[] parts = ip.trim().split("\\.");
        if (parts.length != 4) {
            return false;
        }
        for (String part : parts) {
            try {
                int val = Integer.parseInt(part);
                if (val < 0 || val > 255) {
                    return false;
                }
            } catch (NumberFormatException e) {
                return false;
            }
        }
        return true;
    }

    private static boolean isLoopbackIpv4(String ip) {
        return LOCALHOST_IPV4.equals(ip) || ip.startsWith("127.");
    }

    private static List<String> parseCommaList(String value) {
        if (value == null || value.isBlank()) {
            return Collections.emptyList();
        }
        String[] parts = value.split(",");
        List<String> list = new ArrayList<>(parts.length);
        for (String part : parts) {
            String trimmed = part.trim();
            if (!trimmed.isEmpty()) {
                list.add(trimmed);
            }
        }
        return list;
    }

    /**
     * 获取本机主机名
     *
     * @return 主机名或 "localhost"
     */
    public static String getLocalHostName() {
        try {
            return InetAddress.getLocalHost().getHostName();
        } catch (Throwable ignored) {
            return "localhost";
        }
    }

    /**
     * 检查本地指定端口当前是否空闲可用
     *
     * @param port 待检测的端口号（1 ~ 65535）
     * @return 若当前端口可用返回 true，若被占用或超出有效端口范围返回 false
     */
    public static boolean isPortAvailable(int port) {
        if (port < 1 || port > MAX_PORT) {
            return false;
        }
        try (ServerSocket serverSocket = new ServerSocket(port)) {
            serverSocket.setReuseAddress(true);
            return true;
        } catch (IOException ignored) {
            return false;
        }
    }

    /**
     * 检查本地指定端口当前是否空闲可用（{@link #isPortAvailable(int)} 兼容别名）
     *
     * @param port 待检测的端口号（1 ~ 65535）
     * @return 若当前端口可用返回 true，若被占用或超出有效端口范围返回 false
     */
    public static boolean isUsableLocalPort(int port) {
        return isPortAvailable(port);
    }

    /**
     * 动态寻找系统随机分配的可用端口
     *
     * @return 本地可用端口号
     * @throws IllegalStateException 若无法找到可用端口
     */
    public static int findAvailablePort() {
        try (ServerSocket serverSocket = new ServerSocket(0)) {
            serverSocket.setReuseAddress(true);
            return serverSocket.getLocalPort();
        } catch (IOException e) {
            throw new IllegalStateException("未能分配到系统可用端口", e);
        }
    }

    /**
     * 在指定的端口区间 [minPort, maxPort] 范围内寻找首个可用空闲端口
     *
     * @param minPort 起始端口（包含，需 >= 1）
     * @param maxPort 结束端口（包含，需 <= 65535 且 >= minPort）
     * @return 首个检测到的可用端口号
     * @throws IllegalArgumentException 当端口范围无效时抛出
     * @throws IllegalStateException    若在指定范围内未找到任何可用端口
     */
    public static int findAvailablePort(int minPort, int maxPort) {
        if (minPort < 1 || maxPort > MAX_PORT || minPort > maxPort) {
            throw new IllegalArgumentException("无效的端口探测范围: [" + minPort + ", " + maxPort + "]");
        }

        for (int port = minPort; port <= maxPort; port++) {
            if (isPortAvailable(port)) {
                return port;
            }
        }
        throw new IllegalStateException("在端口区间 [" + minPort + ", " + maxPort + "] 内未找到可用空闲端口");
    }

    /**
     * 检测远程主机与端口的网络套接字可达性（Socket Connectivity Test）
     *
     * @param host          目标主机地址（域名或 IP，不能为空）
     * @param port          目标端口
     * @param timeoutMillis 连接超时时间（毫秒，必须大于 0）
     * @return 若网络连通返回 true，若连接超时或失败返回 false
     */
    public static boolean isReachable(String host, int port, int timeoutMillis) {
        if (host == null || host.isBlank() || port < 1 || port > MAX_PORT || timeoutMillis <= 0) {
            return false;
        }
        try (Socket socket = new Socket()) {
            socket.connect(new InetSocketAddress(host, port), timeoutMillis);
            return true;
        } catch (IOException ignored) {
            return false;
        }
    }

    /**
     * 检测远程主机与端口的网络套接字可达性（{@link #isReachable(String, int, int)} 兼容别名）
     *
     * @param host          目标主机地址（域名或 IP，不能为空）
     * @param port          目标端口
     * @param timeoutMillis 连接超时时间（毫秒，必须大于 0）
     * @return 若网络连通返回 true，若连接超时或失败返回 false
     */
    public static boolean canConnect(String host, int port, int timeoutMillis) {
        return isReachable(host, port, timeoutMillis);
    }
}
