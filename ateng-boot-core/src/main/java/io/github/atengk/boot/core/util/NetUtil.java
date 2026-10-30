package io.github.atengk.boot.core.util;

import java.io.IOException;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.NetworkInterface;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.Enumeration;
import java.util.regex.Pattern;

/**
 * 网络与通信地址通用工具类
 * 提供多网卡内网 IPv4 智能识别解析、本地空闲端口安全探测与远程服务连通性探针
 *
 * @author Ateng
 * @since 2026-10-26
 */
public final class NetUtil {

    public static final String LOCALHOST_IPV4 = "127.0.0.1";
    public static final int MIN_PORT = 1024;
    public static final int MAX_PORT = 65535;

    /**
     * 常见虚拟网卡与隧道接口黑名单名称模式
     */
    private static final Pattern VIRTUAL_INTERFACE_PATTERN = Pattern.compile(
            "(?i).*(docker|veth|vmnet|virbr|vboxnet|hyper-v|wsl|dummy|tailscale|flannel|cni|br-|tap|tun).*"
    );

    private static volatile String cachedLocalIpv4;

    private NetUtil() {
    }

    /**
     * 判断指定网络接口是否为虚拟网卡或无物理介质网卡
     * 自动检测虚拟网卡标识、点对点链路以及 Docker、VMware、WSL 等常见虚拟网卡名称
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
        } catch (Throwable ignored) {
            // 防御接口状态探测异常
        }
        return false;
    }

    /**
     * 清理本机 IPv4 地址缓存（主要用于测试隔离或网络拓扑动态刷新）
     */
    public static void clearLocalIpv4Cache() {
        synchronized (NetUtil.class) {
            cachedLocalIpv4 = null;
        }
    }

    /**
     * 获取本机首选内网非回环 IPv4 地址
     * 自动遍历所有物理网卡，过滤回环、未激活与虚拟网卡，优先获取局域网私有 IPv4 地址
     *
     * @return 本机内网 IPv4 地址；探测失败时安全回退返回 "127.0.0.1"
     */
    public static String getLocalIpv4() {
        if (cachedLocalIpv4 != null) {
            return cachedLocalIpv4;
        }

        synchronized (NetUtil.class) {
            if (cachedLocalIpv4 != null) {
                return cachedLocalIpv4;
            }

            try {
                Enumeration<NetworkInterface> interfaces = NetworkInterface.getNetworkInterfaces();
                InetAddress candidate = null;

                if (interfaces != null) {
                    while (interfaces.hasMoreElements()) {
                        NetworkInterface ni = interfaces.nextElement();
                        if (ni.isLoopback() || !ni.isUp() || isVirtualInterface(ni)) {
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
        }
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
