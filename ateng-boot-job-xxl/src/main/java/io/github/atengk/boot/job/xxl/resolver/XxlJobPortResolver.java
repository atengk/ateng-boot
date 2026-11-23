package io.github.atengk.boot.job.xxl.resolver;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.ServerSocket;

/**
 * XXL-Job 执行器端口防踩踏自愈解析器
 *
 * <p>提供执行器监听端口动态探测、占用检测、自动顺延自愈与随机空闲端口分配能力，
 * 防御因本地多实例并发或僵死进程占用导致的执行器启动失败与端口冲突崩溃。</p>
 *
 * @author Ateng
 * @since 2026-10-31
 */
public final class XxlJobPortResolver {

    private static final Logger log = LoggerFactory.getLogger(XxlJobPortResolver.class);

    private XxlJobPortResolver() {
    }

    /**
     * 解析并获取可用端口
     *
     * <p>若配置端口小于等于 0，自动由系统分配随机可用端口；若配置端口大于 0 且被占用，自动逐级顺延探测直至找到可用端口。</p>
     *
     * @param configuredPort 配置的初始端口
     * @param maxRetrySteps  最大顺延重试步长 (防无限循环)
     * @return 经探测确认可用的空闲端口
     * @throws IllegalStateException 顺延超出重试上限仍未获取到可用端口时抛出
     */
    public static int resolvePort(int configuredPort, int maxRetrySteps) {
        if (configuredPort <= 0) {
            int randomPort = allocateRandomPort();
            log.info("XXL-Job 执行器配置为随机端口 [{}], 成功分配可用端口 [{}]", configuredPort, randomPort);
            return randomPort;
        }

        int currentPort = configuredPort;
        int maxAttempts = Math.max(0, maxRetrySteps);

        for (int step = 0; step <= maxAttempts; step++) {
            if (isPortAvailable(currentPort)) {
                if (currentPort != configuredPort) {
                    log.warn("XXL-Job 执行器初始端口 [{}] 已被占用，已自动顺延至可用端口 [{}] (顺延步长: {})",
                            configuredPort, currentPort, step);
                } else {
                    log.info("XXL-Job 执行器端口可用检测通过，监听端口: [{}]", currentPort);
                }
                return currentPort;
            }
            currentPort++;
        }

        throw new IllegalStateException(String.format(
                "XXL-Job 执行器端口顺延自愈失败: 在范围 [%d, %d] 内未探测到任何可用空闲端口",
                configuredPort, currentPort - 1));
    }

    /**
     * 探测指定端口是否处于可用空闲状态
     *
     * <p>结合客户端连接嗅探与服务端双栈绑定尝试，严防 Windows/Linux 下的 SO_REUSEADDR 假空闲现象。</p>
     *
     * @param port 目标端口
     * @return 若端口空闲且可被正常绑定则返回 true，否则返回 false
     */
    public static boolean isPortAvailable(int port) {
        if (port < 1 || port > 65535) {
            return false;
        }

        // 1. 尝试快速建立 TCP 连接嗅探是否已有服务在监听
        try (java.net.Socket socket = new java.net.Socket()) {
            socket.connect(new InetSocketAddress("127.0.0.1", port), 50);
            // 能连接成功，说明端口已被其他服务占用
            return false;
        } catch (IOException ignored) {
            // 连接失败，继续第二道 ServerSocket 绑定防卫
        }

        // 2. 尝试服务端显式绑定防卫 (严格禁止地址复用)
        try (ServerSocket serverSocket = new ServerSocket()) {
            serverSocket.setReuseAddress(false);
            serverSocket.bind(new InetSocketAddress("0.0.0.0", port), 1);
            return true;
        } catch (IOException e) {
            return false;
        }
    }

    /**
     * 分配一个系统随机可用端口
     *
     * @return 系统分配的可用端口
     */
    public static int allocateRandomPort() {
        try (ServerSocket serverSocket = new ServerSocket(0)) {
            serverSocket.setReuseAddress(true);
            return serverSocket.getLocalPort();
        } catch (IOException e) {
            throw new IllegalStateException("XXL-Job 执行器随机端口分配失败", e);
        }
    }

}
