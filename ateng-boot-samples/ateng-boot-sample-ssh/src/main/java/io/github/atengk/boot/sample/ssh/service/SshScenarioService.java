package io.github.atengk.boot.sample.ssh.service;

import io.github.atengk.boot.ssh.context.SshContextHolder;
import io.github.atengk.boot.ssh.enums.HostKeyPolicy;
import io.github.atengk.boot.ssh.model.SshExecResult;
import io.github.atengk.boot.ssh.model.SshServerInfo;
import io.github.atengk.boot.ssh.template.SshTemplate;
import io.github.atengk.boot.ssh.tunnel.SshTunnelHandle;
import jakarta.annotation.PreDestroy;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;

/**
 * SSH 核心业务底座与高阶场景演练服务
 * <p>
 * 演示非 Web 纯底座高阶特性：<br>
 * 1. ProxyJump 跳板机与级联多跳拓扑穿透；<br>
 * 2. 瞬态单次任务隔离执行 (execOnce)；<br>
 * 3. 本地端口转发受管句柄生命周期与真实 TCP 流量交互；<br>
 * 4. 复杂多行运维脚本管道执行 (execScript)。
 * </p>
 *
 * @author Ateng
 * @since 2026-10-23
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class SshScenarioService {

    private final SshTemplate sshTemplate;

    /**
     * 演示两级跳板机级联穿透执行运维命令 (Client -> Jump1 -> Jump2 -> Target)
     *
     * @param targetHost   目标内网主机
     * @param targetPort   目标 SSH 端口
     * @param jump1Host    跳板机 1 主机
     * @param jump1Port    跳板机 1 端口
     * @param jump2Host    跳板机 2 主机
     * @param jump2Port    跳板机 2 端口
     * @param command      待执行命令
     * @return 执行结果
     */
    public SshExecResult runCascadingProxyJump(String targetHost, int targetPort,
                                              String jump1Host, int jump1Port,
                                              String jump2Host, int jump2Port,
                                              String command) {
        // 构建第一级跳板机
        SshServerInfo jump1 = SshServerInfo.builder()
                .host(jump1Host)
                .port(jump1Port)
                .username("jump1_user")
                .password("jump1_pass")
                .hostKeyPolicy(HostKeyPolicy.IGNORE)
                .build();

        // 构建第二级跳板机，内嵌上一级跳板机 jump1
        SshServerInfo jump2 = SshServerInfo.builder()
                .host(jump2Host)
                .port(jump2Port)
                .username("jump2_user")
                .password("jump2_pass")
                .jumpHost(jump1)
                .hostKeyPolicy(HostKeyPolicy.IGNORE)
                .build();

        // 构建最终目标服务器，内嵌 jump2
        SshServerInfo target = SshServerInfo.builder()
                .host(targetHost)
                .port(targetPort)
                .username("target_user")
                .password("target_pass")
                .jumpHost(jump2)
                .hostKeyPolicy(HostKeyPolicy.IGNORE)
                .build();

        log.info("ateng-boot-ssh-sample: 正在通过两级级联跳板机执行运维命令 [{}]", command);
        return sshTemplate.exec(target, command);
    }

    /**
     * 演示瞬态单次任务隔离执行（执行完毕后底层 Session 立即确定性物理关闭断开）
     *
     * @param serverInfo 目标描述符
     * @param command    待执行命令
     * @return 执行结果
     */
    public SshExecResult runTransientTask(SshServerInfo serverInfo, String command) {
        log.info("ateng-boot-ssh-sample: 正在执行瞬态单次隔离任务 [{}]", command);
        return sshTemplate.execOnce(serverInfo, command);
    }

    /**
     * 演示本地端口转发隧道建立并完成端到端 TCP 通信
     *
     * @param remoteHost 目标内网服务主机
     * @param remotePort 目标内网服务端口
     * @param message    测试发送的文本
     * @return 远端服务回显内容
     * @throws Exception 通信异常
     */
    public String forwardAndQueryTcp(String remoteHost, int remotePort, String message) throws Exception {
        // 开启本地端口转发（0 端口由操作系统动态分配）
        try (SshTunnelHandle tunnel = sshTemplate.openLocalTunnel(0, remoteHost, remotePort)) {
            int localPort = tunnel.getLocalPort();
            log.info("ateng-boot-ssh-sample: 本地端口转发隧道已就绪 [127.0.0.1:{} -> {}:{}]",
                    localPort, remoteHost, remotePort);

            // 通过本地转发端口发起 TCP 请求
            try (Socket socket = new Socket("127.0.0.1", localPort);
                 BufferedWriter writer = new BufferedWriter(new OutputStreamWriter(socket.getOutputStream(), StandardCharsets.UTF_8));
                 BufferedReader reader = new BufferedReader(new InputStreamReader(socket.getInputStream(), StandardCharsets.UTF_8))) {

                socket.setSoTimeout(3000);
                writer.write(message + "\n");
                writer.flush();

                return reader.readLine();
            }
        }
    }

    /**
     * 当前服务受管的活跃端口转发句柄映射字典 (key: localPort)
     */
    private final Map<Integer, SshTunnelHandle> managedTunnels = new ConcurrentHashMap<>();

    /**
     * 演示建立受管的本地端口转发隧道
     *
     * @param serverName 节点名称
     * @param localPort  本地期望绑定端口（0 为动态分配）
     * @param remoteHost 目标内网主机
     * @param remotePort 目标内网端口
     * @return 实际绑定的本地端口
     */
    public int openManagedTunnel(String serverName, int localPort, String remoteHost, int remotePort) {
        SshTunnelHandle handle = (serverName != null && !serverName.isBlank())
                ? sshTemplate.openLocalTunnel(serverName, localPort, remoteHost, remotePort)
                : sshTemplate.openLocalTunnel(localPort, remoteHost, remotePort);
        int boundPort = handle.getLocalPort();
        managedTunnels.put(boundPort, handle);
        log.info("ateng-boot-ssh-sample: 受管端口转发隧道已注册 [localPort={}, remote={}:{}]", boundPort, remoteHost, remotePort);
        return boundPort;
    }

    /**
     * 对称释放受管的本地端口转发隧道
     *
     * @param localPort 本地绑定端口
     * @return true 若成功释放
     */
    public boolean closeManagedTunnel(int localPort) {
        SshTunnelHandle handle = managedTunnels.remove(localPort);
        if (handle != null) {
            handle.close();
            log.info("ateng-boot-ssh-sample: 受管端口转发隧道已安全释放 [localPort={}]", localPort);
            return true;
        }
        return false;
    }

    /**
     * 演示通过 SshContextHolder 动态切源至目标节点执行命令
     *
     * @param serverName 目标节点标识
     * @param command    待执行命令
     * @return 远端标准输出
     */
    public String executeWithContextSwitch(String serverName, String command) {
        log.info("ateng-boot-ssh-sample: 正在通过 SshContextHolder 动态切源至节点 [{}] 执行命令 [{}]", serverName, command);
        return SshContextHolder.runWith(serverName, () -> {
            SshExecResult result = sshTemplate.exec(command);
            return result.getStdout().trim();
        });
    }

    /**
     * 演示底座长命令流式按行消费（零内存缓冲防 OOM）
     *
     * @param command      待执行长命令
     * @param lineConsumer 行日志消费者
     * @return 进程退出状态码
     */
    public int streamLogs(String command, Consumer<String> lineConsumer) {
        log.info("ateng-boot-ssh-sample: 正在以零内存缓冲流式消费命令日志 [{}]", command);
        return sshTemplate.execStream(command, lineConsumer);
    }

    /**
     * 演示下发多行运维脚本序列
     *
     * @param scriptBody 脚本内容
     * @return 执行结果
     */
    public SshExecResult runMaintenanceScript(String scriptBody) {
        log.info("ateng-boot-ssh-sample: 正在下发多行运维脚本管道...");
        return sshTemplate.execScript(scriptBody);
    }

    @PreDestroy
    public void closeAllTunnels() {
        for (SshTunnelHandle handle : managedTunnels.values()) {
            try {
                handle.close();
            } catch (Exception ignored) {
            }
        }
        managedTunnels.clear();
    }
}
