package io.github.atengk.boot.sample.ssh.service;

import io.github.atengk.boot.ssh.context.SshContextHolder;
import io.github.atengk.boot.ssh.enums.HostKeyPolicy;
import io.github.atengk.boot.ssh.exception.SshOperationException;
import io.github.atengk.boot.ssh.exception.SshResultCode;
import io.github.atengk.boot.ssh.model.SshBatchExecResult;
import io.github.atengk.boot.ssh.model.SshBatchOptions;
import io.github.atengk.boot.ssh.model.SshExecOptions;
import io.github.atengk.boot.ssh.model.SshExecResult;
import io.github.atengk.boot.ssh.model.SshPoolStats;
import io.github.atengk.boot.ssh.model.SshServerInfo;
import io.github.atengk.boot.ssh.properties.SshProperties;
import io.github.atengk.boot.ssh.registry.SshClientRegistry;
import io.github.atengk.boot.ssh.template.SshTemplate;
import io.github.atengk.boot.ssh.tunnel.SshTunnelHandle;
import io.github.atengk.boot.ssh.verifier.InMemoryServerKeyVerifier;
import jakarta.annotation.PreDestroy;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.sshd.client.keyverifier.ServerKeyVerifier;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;
import org.springframework.util.unit.DataSize;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.Collection;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;

/**
 * SSH 核心业务底座与高阶场景演练服务
 * <p>
 * 演示非 Web 纯底座高阶特性：<br>
 * 1. 同步与非阻塞异步命令执行 (exec / execAsync)；<br>
 * 2. 原生 Sudo 提权管道与密码内存抹零 (execSudo)；<br>
 * 3. 命名节点无害轻量连通性探针 (testConnection)；<br>
 * 4. 多节点高级批量聚合巡检 (execBatchAdvanced)；<br>
 * 5. ProxyJump 跳板机与级联多跳拓扑穿透；<br>
 * 6. 瞬态单次任务隔离执行 (execOnce)；<br>
 * 7. 本地端口转发受管句柄生命周期与真实 TCP 流量交互；<br>
 * 8. 长命令流式按行消费 (execStream) 与多行运维脚本管道 (execScript)。
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
    private final SshClientRegistry sshClientRegistry;
    private final ObjectProvider<ServerKeyVerifier> serverKeyVerifierProvider;

    /**
     * 当前服务受管的活跃本地端口转发句柄映射字典 (key: localPort)
     */
    private final Map<Integer, SshTunnelHandle> managedTunnels = new ConcurrentHashMap<>();

    /**
     * 当前服务受管的活跃远程端口转发句柄映射字典 (key: remotePort)
     */
    private final Map<Integer, SshTunnelHandle> managedRemoteTunnels = new ConcurrentHashMap<>();

    /**
     * 演示默认主节点同步命令执行
     *
     * @param command 待执行命令
     * @return 执行结果
     */
    public SshExecResult exec(String command) {
        log.info("ateng-boot-ssh-sample: 正在执行同步命令 [{}]", command);
        return sshTemplate.exec(command);
    }

    /**
     * 演示指定命名节点同步命令执行
     *
     * @param serverName 目标节点名称
     * @param command    待执行命令
     * @return 执行结果
     */
    public SshExecResult exec(String serverName, String command) {
        return exec(serverName, command, SshExecOptions.defaults());
    }

    /**
     * 演示指定命名节点同步命令执行（支持按最大输出字节数覆盖限制）
     *
     * @param serverName     目标节点名称（可为空）
     * @param command        待执行命令
     * @param maxOutputBytes 最大输出缓冲字节上限（可为 null）
     * @return 执行结果
     */
    public SshExecResult exec(String serverName, String command, Long maxOutputBytes) {
        SshExecOptions options = maxOutputBytes != null
                ? SshExecOptions.builder().maxOutputSize(DataSize.ofBytes(maxOutputBytes)).build()
                : SshExecOptions.defaults();
        return exec(serverName, command, options);
    }

    /**
     * 演示指定命名节点同步命令执行（支持执行选项覆盖）
     *
     * @param serverName 目标节点名称（可为空）
     * @param command    待执行命令
     * @param options    命令执行参数选项
     * @return 执行结果
     */
    public SshExecResult exec(String serverName, String command, SshExecOptions options) {
        SshExecOptions effectiveOptions = options != null ? options : SshExecOptions.defaults();
        if (!org.springframework.util.StringUtils.hasText(serverName)) {
            log.info("ateng-boot-ssh-sample: 正在执行同步命令 [{}]", command);
            return sshTemplate.exec(command, effectiveOptions);
        }
        log.info("ateng-boot-ssh-sample: 正在目标节点 [{}] 执行同步命令 [{}]", serverName, command);
        return sshTemplate.exec(serverName, command, effectiveOptions);
    }

    /**
     * 演示纯内存动态直连描述符同步执行
     *
     * @param serverInfo 目标节点描述符
     * @param command    待执行命令
     * @return 执行结果
     */
    public SshExecResult exec(SshServerInfo serverInfo, String command) {
        log.info("ateng-boot-ssh-sample: 正在动态直连主机 [{}:{}] 执行命令 [{}]",
                serverInfo.getHost(), serverInfo.getPort(), command);
        return sshTemplate.exec(serverInfo, command);
    }

    /**
     * 演示非阻塞异步命令执行，返回 CompletableFuture 并在后台线程池中完成
     *
     * @param command 待执行命令
     * @return 异步执行 Future
     */
    public CompletableFuture<SshExecResult> execAsync(String command) {
        log.info("ateng-boot-ssh-sample: 正在下发非阻塞异步任务 [{}]", command);
        return sshTemplate.execAsync(command);
    }

    /**
     * 演示指定命名节点的非阻塞异步命令执行
     *
     * @param serverName 节点名称
     * @param command    待执行命令
     * @return 异步执行 Future
     */
    public CompletableFuture<SshExecResult> execAsync(String serverName, String command) {
        if (!org.springframework.util.StringUtils.hasText(serverName)) {
            return execAsync(command);
        }
        log.info("ateng-boot-ssh-sample: 正在指定节点 [{}] 下发非阻塞异步任务 [{}]", serverName, command);
        return sshTemplate.execAsync(serverName, command);
    }

    /**
     * 演示原生 Sudo 提权管道执行（密码即时擦除抹零防泄露）
     *
     * @param command      待执行命令
     * @param sudoPassword Sudo 密码
     * @return 执行结果
     */
    public SshExecResult execSudo(String command, String sudoPassword) {
        log.info("ateng-boot-ssh-sample: 正在通过 Sudo 安全管道提权执行 [{}]", command);
        return sshTemplate.execSudo(command, sudoPassword);
    }

    /**
     * 演示指定节点的原生 Sudo 提权管道执行
     *
     * @param serverName   目标节点名称
     * @param command      待执行命令
     * @param sudoPassword Sudo 密码
     * @return 执行结果
     */
    public SshExecResult execSudo(String serverName, String command, String sudoPassword) {
        if (!org.springframework.util.StringUtils.hasText(serverName)) {
            return execSudo(command, sudoPassword);
        }
        log.info("ateng-boot-ssh-sample: 正在目标节点 [{}] 通过 Sudo 提权执行 [{}]", serverName, command);
        return sshTemplate.execSudo(serverName, command, sudoPassword);
    }

    /**
     * 演示无害快速连通性探针（仅完成 SSH 握手鉴权即刻断开，不分配伪终端或执行进程）
     *
     * @param serverName 目标节点名称
     * @return true 若连通且鉴权成功
     */
    public boolean testConnection(String serverName) {
        log.info("ateng-boot-ssh-sample: 正在探测节点 [{}] 连通性", serverName);
        return sshTemplate.testConnection(serverName);
    }

    /**
     * 演示动态主机无害快速连通性探针
     *
     * @param serverInfo 目标节点描述符
     * @return true 若连通且鉴权成功
     */
    public boolean testConnection(SshServerInfo serverInfo) {
        log.info("ateng-boot-ssh-sample: 正在探测动态节点 [{}:{}] 连通性", serverInfo.getHost(), serverInfo.getPort());
        return sshTemplate.testConnection(serverInfo);
    }

    /**
     * 演示多节点高级批量巡检，支持并发度限流、失败快速熔断与聚合统计报告
     *
     * @param servers 目标节点集合
     * @param command 待巡检命令
     * @param options 批量巡检高级控制选项
     * @return 聚合统计结果
     */
    public SshBatchExecResult runBatchInspection(Collection<SshServerInfo> servers, String command, SshBatchOptions options) {
        log.info("ateng-boot-ssh-sample: 正在对 [{}] 个动态节点执行批量巡检 [{}]", servers != null ? servers.size() : 0, command);
        return sshTemplate.execBatchAdvanced(servers, command, options);
    }

    /**
     * 演示按命名节点集合高级批量巡检
     *
     * @param serverNames 节点名称集合
     * @param command     待巡检命令
     * @param options     批量巡检高级控制选项
     * @return 聚合统计结果
     */
    public SshBatchExecResult runBatchInspectionNamed(Collection<String> serverNames, String command, SshBatchOptions options) {
        log.info("ateng-boot-ssh-sample: 正在对命名节点集合 {} 执行批量巡检 [{}]", serverNames, command);
        return sshTemplate.execBatchAdvancedNamed(serverNames, command, options);
    }

    /**
     * 演示两级跳板机级联穿透执行运维命令 (Client -> Jump1 -> Jump2 -> Target)
     *
     * @param targetHost 目标内网主机
     * @param targetPort 目标 SSH 端口
     * @param jump1Host  跳板机 1 主机
     * @param jump1Port  跳板机 1 端口
     * @param jump2Host  跳板机 2 主机
     * @param jump2Port  跳板机 2 端口
     * @param command    待执行命令
     * @return 执行结果
     */
    public SshExecResult runCascadingProxyJump(String targetHost, int targetPort,
                                              String jump1Host, int jump1Port,
                                              String jump2Host, int jump2Port,
                                              String command) {
        SshServerInfo jump1 = SshServerInfo.builder()
                .host(jump1Host)
                .port(jump1Port)
                .username("jump1_user")
                .password("jump1_pass")
                .hostKeyPolicy(HostKeyPolicy.IGNORE)
                .build();

        SshServerInfo jump2 = SshServerInfo.builder()
                .host(jump2Host)
                .port(jump2Port)
                .username("jump2_user")
                .password("jump2_pass")
                .jumpHost(jump1)
                .hostKeyPolicy(HostKeyPolicy.IGNORE)
                .build();

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
        try (SshTunnelHandle tunnel = sshTemplate.openLocalTunnel(0, remoteHost, remotePort)) {
            int localPort = tunnel.getLocalPort();
            log.info("ateng-boot-ssh-sample: 本地端口转发隧道已就绪 [127.0.0.1:{} -> {}:{}]",
                    localPort, remoteHost, remotePort);
            return exchangeTcpLine("127.0.0.1", localPort, message);
        }
    }

    /**
     * 演示远程端口转发与反向穿透 TCP 流量回显验证 (ssh -R)
     *
     * @param serverName 远端 SSH 节点名称（可空，缺省默认节点）
     * @param remotePort 远端绑定端口（0 表示动态分配）
     * @param localHost  本地目标服务主机
     * @param localPort  本地目标服务端口
     * @param message    测试发送的文本
     * @return 经由反向隧道回传的响应内容
     * @throws Exception 通信异常
     */
    public String reverseForwardAndQueryTcp(String serverName,
                                            int remotePort,
                                            String localHost,
                                            int localPort,
                                            String message) throws Exception {
        try (SshTunnelHandle handle = sshTemplate.openRemoteTunnel(serverName, remotePort, localHost, localPort)) {
            int boundRemotePort = handle.getRemotePort();
            String connectHost = resolveRemoteConnectHost(handle);
            log.info("ateng-boot-ssh-sample: 远程端口转发隧道已就绪 [{}:{} -> {}:{}]",
                    connectHost, boundRemotePort, localHost, localPort);
            return exchangeTcpLine(connectHost, boundRemotePort, message);
        }
    }

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
     * 演示建立受管的远程端口转发隧道 (ssh -R)
     *
     * @param serverName 节点名称
     * @param remotePort 远端期望绑定端口（0 为动态分配）
     * @param localHost  本地目标服务主机
     * @param localPort  本地目标服务端口
     * @return 受管远程端口转发句柄
     */
    public SshTunnelHandle openManagedRemoteTunnel(String serverName, int remotePort, String localHost, int localPort) {
        SshTunnelHandle handle = sshTemplate.openRemoteTunnel(serverName, remotePort, localHost, localPort);
        managedRemoteTunnels.put(handle.getRemotePort(), handle);
        log.info("ateng-boot-ssh-sample: 受管远程端口转发隧道已注册 [remotePort={}, local={}:{}]",
                handle.getRemotePort(), localHost, localPort);
        return handle;
    }

    /**
     * 对称释放受管的本地端口转发隧道
     *
     * @param localPort 本地绑定端口
     * @return true 若成功释放
     */
    public boolean closeManagedTunnel(int localPort) {
        return closeManagedTunnelByKey(managedTunnels, localPort, "localPort");
    }

    /**
     * 对称释放受管的远程端口转发隧道
     *
     * @param remotePort 远端绑定端口
     * @return true 若成功释放
     */
    public boolean closeManagedRemoteTunnel(int remotePort) {
        return closeManagedTunnelByKey(managedRemoteTunnels, remotePort, "remotePort");
    }

    private boolean closeManagedTunnelByKey(Map<Integer, SshTunnelHandle> tunnelMap, int port, String label) {
        SshTunnelHandle handle = tunnelMap.remove(port);
        if (handle != null) {
            handle.close();
            log.info("ateng-boot-ssh-sample: 受管端口转发隧道已安全释放 [{}={}]", label, port);
            return true;
        }
        return false;
    }

    private String exchangeTcpLine(String host, int port, String message) throws Exception {
        try (Socket socket = new Socket()) {
            socket.connect(new InetSocketAddress(host, port), 3000);
            socket.setSoTimeout(3000);
            try (BufferedWriter writer = new BufferedWriter(new OutputStreamWriter(socket.getOutputStream(), StandardCharsets.UTF_8));
                 BufferedReader reader = new BufferedReader(new InputStreamReader(socket.getInputStream(), StandardCharsets.UTF_8))) {
                writer.write(message + "\n");
                writer.flush();
                return reader.readLine();
            }
        }
    }

    private static String resolveRemoteConnectHost(SshTunnelHandle handle) {
        String remoteHost = handle.getRemoteHost();
        if (remoteHost != null && !remoteHost.isBlank()
                && !"0.0.0.0".equals(remoteHost) && !"::".equals(remoteHost)) {
            return remoteHost;
        }
        if (handle.getServerInfo() != null && handle.getServerInfo().getHost() != null
                && !handle.getServerInfo().getHost().isBlank()) {
            return handle.getServerInfo().getHost();
        }
        return "127.0.0.1";
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
     * 演示指定节点长命令流式按行消费
     *
     * @param serverName   目标节点名称
     * @param command      待执行长命令
     * @param lineConsumer 行日志消费者
     * @return 进程退出状态码
     */
    public int streamLogs(String serverName, String command, Consumer<String> lineConsumer) {
        if (serverName != null && !serverName.isBlank()) {
            log.info("ateng-boot-ssh-sample: 正在目标节点 [{}] 以零内存缓冲流式消费命令日志 [{}]", serverName, command);
            return sshTemplate.execStream(serverName, command, lineConsumer, null, io.github.atengk.boot.ssh.model.SshExecOptions.defaults());
        }
        return streamLogs(command, lineConsumer);
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

    /**
     * 演示在指定节点下发多行运维脚本序列
     *
     * @param serverName 目标节点名称
     * @param scriptBody 脚本内容
     * @return 执行结果
     */
    public SshExecResult runMaintenanceScript(String serverName, String scriptBody) {
        if (serverName != null && !serverName.isBlank()) {
            log.info("ateng-boot-ssh-sample: 正在目标节点 [{}] 下发多行运维脚本管道...", serverName);
            return sshTemplate.execScript(serverName, scriptBody);
        }
        return runMaintenanceScript(scriptBody);
    }

    /**
     * 获取受管会话缓存池当前运行态统计快照
     *
     * @return 会话池统计快照
     */
    public SshPoolStats getPoolStats() {
        return sshTemplate.getPoolStats();
    }

    /**
     * 动态注册具名 SSH 服务节点至注册表（支持指定 jumpHost 跳板机节点名称）
     *
     * @param serverName 节点名称
     * @param host       主机地址
     * @param port       SSH 端口
     * @param username   用户名
     * @param password   口令密码
     * @param jumpHost   跳板机节点名称（可为空）
     */
    public void registerManagedNode(String serverName, String host, int port, String username, String password, String jumpHost) {
        SshProperties props = new SshProperties();
        props.setHost(host);
        props.setPort(port);
        props.setUsername(username);
        props.setPassword(password);
        props.setHostKeyPolicy(HostKeyPolicy.LENIENT);
        if (jumpHost != null && !jumpHost.isBlank()) {
            props.setJumpHost(jumpHost);
        }
        sshClientRegistry.register(serverName, props);
    }

    /**
     * 动态注销具名 SSH 服务节点并自动联动会话池驱逐旧连接
     *
     * @param serverName 节点名称
     * @return true 若节点存在并已注销驱逐
     */
    public boolean unregisterManagedNode(String serverName) {
        return sshClientRegistry.unregister(serverName) != null;
    }

    /**
     * 定点移除指定主机在内存指纹校验器中缓存的公钥指纹
     *
     * @param host 目标主机地址
     * @param port 目标端口
     * @return true 若原先存在指纹缓存并已移除
     */
    public boolean removeKnownHostKey(String host, int port) {
        ServerKeyVerifier verifier = serverKeyVerifierProvider.getIfAvailable();
        if (!(verifier instanceof InMemoryServerKeyVerifier memoryVerifier)) {
            throw new SshOperationException(SshResultCode.OPERATION_FAILED,
                    "当前 ServerKeyVerifier 不是 InMemoryServerKeyVerifier，无法执行内存指纹移除");
        }
        return memoryVerifier.removeKnownKey(host, port) != null;
    }

    @PreDestroy
    public void closeAllTunnels() {
        closeTunnelMap(managedTunnels);
        closeTunnelMap(managedRemoteTunnels);
    }

    private void closeTunnelMap(Map<Integer, SshTunnelHandle> tunnelMap) {
        for (SshTunnelHandle handle : tunnelMap.values()) {
            try {
                handle.close();
            } catch (Exception e) {
                log.warn("ateng-boot-ssh-sample: 销毁清理受管端口转发隧道异常: {}", e.getMessage());
            }
        }
        tunnelMap.clear();
    }
}
