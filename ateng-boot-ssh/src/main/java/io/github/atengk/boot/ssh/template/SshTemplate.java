package io.github.atengk.boot.ssh.template;

import io.github.atengk.boot.core.context.AsyncContext;
import io.github.atengk.boot.ssh.context.SshContextHolder;
import io.github.atengk.boot.ssh.core.SshCommandExecutor;
import io.github.atengk.boot.ssh.exception.SshOperationException;
import io.github.atengk.boot.ssh.exception.SshResultCode;
import io.github.atengk.boot.ssh.lifecycle.SshLifecycleManager;
import io.github.atengk.boot.ssh.model.SshExecOptions;
import io.github.atengk.boot.ssh.model.SshExecResult;
import io.github.atengk.boot.ssh.model.SshServerInfo;
import io.github.atengk.boot.ssh.model.SshShellOptions;
import io.github.atengk.boot.ssh.pool.SshSessionAction;
import io.github.atengk.boot.ssh.pool.SshSessionCallback;
import io.github.atengk.boot.ssh.pool.SshSessionPool;
import io.github.atengk.boot.ssh.properties.SshProperties;
import io.github.atengk.boot.ssh.registry.SshClientRegistry;
import io.github.atengk.boot.ssh.shell.SshShellHandle;
import io.github.atengk.boot.ssh.tunnel.SshTunnelHandle;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;
import org.apache.sshd.client.channel.ChannelShell;
import org.apache.sshd.client.session.ClientSession;
import org.apache.sshd.client.session.forward.ExplicitPortForwardingTracker;
import org.apache.sshd.common.util.net.SshdSocketAddress;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.util.Assert;

import java.io.IOException;

import java.time.Duration;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.function.Consumer;
import java.util.function.Supplier;

/**
 * SSH 核心操作门面 (SshTemplate)
 * <p>
 * 1. 深度融通四位一体路由：透明支持缺省节点、YAML 命名注册表、线程上下文 (SshContextHolder) 与 CMDB 动态直连描述符 (SshServerInfo)；<br>
 * 2. 对称涵盖同步短命令 (exec)、异步非阻塞命令 (execAsync)、长任务实时按行流式消费 (execStream)、多行复杂脚本管道 (execScript/execScriptAsync) 与瞬态单次任务隔离 (execOnce)；<br>
 * 3. 创新落地原生 Sudo 提权安全管道 (execSudo) 与密码即时内存擦除抹零 (In-Memory Scrubbing)；<br>
 * 4. 深度联动 Core 受管线程池 (ThreadPoolTaskExecutor) 与四维上下文快照 (MDC/User/Tenant) 实现集群多节点高并发批量巡检 (execBatch)；<br>
 * 5. 开箱即用支持本地端口转发受管隧道 (openLocalTunnel) 与跳板机中继穿透；<br>
 * 6. 深度支持交互式 PTY 伪终端受管句柄 (openShell)，与 WebSSH / xterm.js 协同并联动生命周期自动停机排空；<br>
 * 7. 底层依托受管会话池 (SshSessionPool) 与孤儿进程信号主动防卫机制，保障高并发生产环境下的极致性能与资源确定性释放；<br>
 * 8. 提供轻量无害连通性自验健康探针 (testConnection/testConnectionOrThrow)，仅完成 TCP 三次握手与 SSH 凭据身份验证，100% 绝不开辟任何业务 Channel，安全无副作用。
 * </p>
 *
 * @author Ateng
 * @since 2026-10-23
 */
@Slf4j
public class SshTemplate implements AutoCloseable, DisposableBean {

    @Getter
    private final SshSessionPool sessionPool;

    @Getter
    private final SshClientRegistry clientRegistry;

    @Getter
    private final SshProperties properties;

    private final Executor executor;

    @Getter
    private final SshLifecycleManager lifecycleManager;

    private volatile ThreadPoolTaskExecutor internalExecutor;

    public SshTemplate(SshSessionPool sessionPool,
                       SshClientRegistry clientRegistry,
                       SshProperties properties) {
        this(sessionPool, clientRegistry, properties, null, null);
    }

    public SshTemplate(SshSessionPool sessionPool,
                       SshClientRegistry clientRegistry,
                       SshProperties properties,
                       Executor executor) {
        this(sessionPool, clientRegistry, properties, executor, null);
    }

    public SshTemplate(SshSessionPool sessionPool,
                       SshClientRegistry clientRegistry,
                       SshProperties properties,
                       Executor executor,
                       SshLifecycleManager lifecycleManager) {
        Assert.notNull(sessionPool, "SshSessionPool must not be null");
        Assert.notNull(clientRegistry, "SshClientRegistry must not be null");
        this.sessionPool = sessionPool;
        this.clientRegistry = clientRegistry;
        this.properties = properties != null ? properties : new SshProperties();
        this.executor = executor;
        this.lifecycleManager = lifecycleManager;
    }

    // ==========================================
    // 1. 同步命令执行契约 (exec)
    // ==========================================

    /**
     * 在上下文解析的目标服务器上同步执行命令
     *
     * @param command 待执行命令
     * @return 强类型命令执行结果
     */
    public SshExecResult exec(String command) {
        return exec(command, SshExecOptions.defaults());
    }

    /**
     * 在上下文解析的目标服务器上同步执行命令（携带扩展参数）
     *
     * @param command 待执行命令
     * @param options 参数选项
     * @return 强类型命令执行结果
     */
    public SshExecResult exec(String command, SshExecOptions options) {
        return exec((SshServerInfo) null, command, options);
    }

    /**
     * 在指定具名服务器节点上同步执行命令
     *
     * @param serverName 节点名称
     * @param command    待执行命令
     * @return 强类型命令执行结果
     */
    public SshExecResult exec(String serverName, String command) {
        return exec(serverName, command, SshExecOptions.defaults());
    }

    /**
     * 在指定具名服务器节点上同步执行命令（携带扩展参数）
     *
     * @param serverName 节点名称
     * @param command    待执行命令
     * @param options    参数选项
     * @return 强类型命令执行结果
     */
    public SshExecResult exec(String serverName, String command, SshExecOptions options) {
        return exec(resolveServerInfo(serverName), command, options);
    }

    /**
     * 在指定的目标服务器描述符上同步执行命令
     *
     * @param serverInfo 目标描述符
     * @param command    待执行命令
     * @return 强类型命令执行结果
     */
    public SshExecResult exec(SshServerInfo serverInfo, String command) {
        return exec(serverInfo, command, SshExecOptions.defaults());
    }

    /**
     * 在指定的目标服务器描述符上同步执行命令（携带扩展参数）
     *
     * @param serverInfo 目标描述符
     * @param command    待执行命令
     * @param options    参数选项
     * @return 强类型命令执行结果
     */
    public SshExecResult exec(SshServerInfo serverInfo, String command, SshExecOptions options) {
        SshServerInfo target = resolveServerInfo(serverInfo);
        Duration timeout = resolveDefaultTimeout(target);
        return sessionPool.execute(target, session ->
                SshCommandExecutor.executeSync(session, command, options, timeout));
    }

    // ==========================================
    // 1.1 异步非阻塞命令执行契约 (execAsync)
    // ==========================================

    /**
     * 在上下文解析的目标服务器上异步非阻塞执行命令（默认选项，全链路透传四维上下文）
     *
     * @param command 待执行命令
     * @return 强类型命令执行结果异步凭据 (CompletableFuture)
     */
    public CompletableFuture<SshExecResult> execAsync(String command) {
        return execAsync(command, SshExecOptions.defaults());
    }

    /**
     * 在上下文解析的目标服务器上异步非阻塞执行命令（携带扩展配置）
     *
     * @param command 待执行命令
     * @param options 参数配置选项
     * @return 强类型命令执行结果异步凭据 (CompletableFuture)
     */
    public CompletableFuture<SshExecResult> execAsync(String command, SshExecOptions options) {
        return execAsync((SshServerInfo) null, command, options);
    }

    /**
     * 在指定具名服务器节点上异步非阻塞执行命令
     *
     * @param serverName 节点名称
     * @param command    待执行命令
     * @return 强类型命令执行结果异步凭据 (CompletableFuture)
     */
    public CompletableFuture<SshExecResult> execAsync(String serverName, String command) {
        return execAsync(serverName, command, SshExecOptions.defaults());
    }

    /**
     * 在指定具名服务器节点上异步非阻塞执行命令（携带扩展配置）
     *
     * @param serverName 节点名称
     * @param command    待执行命令
     * @param options    参数配置选项
     * @return 强类型命令执行结果异步凭据 (CompletableFuture)
     */
    public CompletableFuture<SshExecResult> execAsync(String serverName, String command, SshExecOptions options) {
        return execAsync(resolveServerInfo(serverName), command, options);
    }

    /**
     * 在指定目标服务器描述符上异步非阻塞执行命令
     *
     * @param serverInfo 目标描述符
     * @param command    待执行命令
     * @return 强类型命令执行结果异步凭据 (CompletableFuture)
     */
    public CompletableFuture<SshExecResult> execAsync(SshServerInfo serverInfo, String command) {
        return execAsync(serverInfo, command, SshExecOptions.defaults());
    }

    /**
     * 在指定目标服务器描述符上异步非阻塞执行命令（携带扩展配置，联动 Core 受管线程池）
     *
     * @param serverInfo 目标描述符
     * @param command    待执行命令
     * @param options    参数配置选项
     * @return 强类型命令执行结果异步凭据 (CompletableFuture)
     */
    public CompletableFuture<SshExecResult> execAsync(SshServerInfo serverInfo, String command, SshExecOptions options) {
        SshServerInfo target = resolveServerInfo(serverInfo);
        SshExecOptions effectiveOptions = options != null ? options : SshExecOptions.defaults();
        Executor targetExecutor = getEffectiveExecutor();
        Supplier<SshExecResult> task = AsyncContext.wrapSupplier(() -> exec(target, command, effectiveOptions));
        return CompletableFuture.supplyAsync(task, targetExecutor);
    }

    // ==========================================
    // 2. 实时按行流式消费契约 (execStream)
    // ==========================================

    /**
     * 实时按行流式执行命令（标准输出回调）
     *
     * @param command        待执行命令
     * @param stdoutConsumer 标准输出按行消费器
     * @return 进程退出状态码
     */
    public int execStream(String command, Consumer<String> stdoutConsumer) {
        return execStream(command, stdoutConsumer, null, SshExecOptions.defaults());
    }

    /**
     * 实时按行流式执行命令（标准输出与标准错误回调）
     *
     * @param command        待执行命令
     * @param stdoutConsumer 标准输出按行消费器
     * @param stderrConsumer 标准错误按行消费器
     * @return 进程退出状态码
     */
    public int execStream(String command, Consumer<String> stdoutConsumer, Consumer<String> stderrConsumer) {
        return execStream(command, stdoutConsumer, stderrConsumer, SshExecOptions.defaults());
    }

    /**
     * 实时按行流式执行命令（携带扩展参数）
     *
     * @param command        待执行命令
     * @param stdoutConsumer 标准输出按行消费器
     * @param stderrConsumer 标准错误按行消费器
     * @param options        参数选项
     * @return 进程退出状态码
     */
    public int execStream(String command,
                          Consumer<String> stdoutConsumer,
                          Consumer<String> stderrConsumer,
                          SshExecOptions options) {
        return execStream((SshServerInfo) null, command, stdoutConsumer, stderrConsumer, options);
    }

    /**
     * 在指定具名节点上实时按行流式执行命令
     *
     * @param serverName     节点名称
     * @param command        待执行命令
     * @param stdoutConsumer 标准输出按行消费器
     * @param stderrConsumer 标准错误按行消费器
     * @param options        参数选项
     * @return 进程退出状态码
     */
    public int execStream(String serverName,
                          String command,
                          Consumer<String> stdoutConsumer,
                          Consumer<String> stderrConsumer,
                          SshExecOptions options) {
        return execStream(resolveServerInfo(serverName), command, stdoutConsumer, stderrConsumer, options);
    }

    /**
     * 在指定目标描述符上实时按行流式执行命令
     *
     * @param serverInfo     目标描述符
     * @param command        待执行命令
     * @param stdoutConsumer 标准输出按行消费器
     * @param stderrConsumer 标准错误按行消费器
     * @param options        参数选项
     * @return 进程退出状态码
     */
    public int execStream(SshServerInfo serverInfo,
                          String command,
                          Consumer<String> stdoutConsumer,
                          Consumer<String> stderrConsumer,
                          SshExecOptions options) {
        SshServerInfo target = resolveServerInfo(serverInfo);
        Duration timeout = resolveDefaultTimeout(target);
        return sessionPool.execute(target, session ->
                SshCommandExecutor.executeStream(session, command, stdoutConsumer, stderrConsumer, options, timeout));
    }

    // ==========================================
    // 3. 多行脚本执行契约 (execScript)
    // ==========================================

    /**
     * 在上下文解析的目标服务器上执行多行脚本序列
     *
     * @param script 包含换行符的多行脚本内容
     * @return 强类型命令执行结果
     */
    public SshExecResult execScript(String script) {
        return execScript((SshServerInfo) null, script, SshExecOptions.defaults());
    }

    /**
     * 在上下文解析的目标服务器上执行多行脚本序列（携带扩展参数）
     *
     * @param script  包含换行符的多行脚本内容
     * @param options 参数选项
     * @return 强类型命令执行结果
     */
    public SshExecResult execScript(String script, SshExecOptions options) {
        return execScript((SshServerInfo) null, script, options);
    }

    /**
     * 在指定具名服务器节点上执行多行脚本序列
     *
     * @param serverName 节点名称
     * @param script     多行脚本内容
     * @return 强类型命令执行结果
     */
    public SshExecResult execScript(String serverName, String script) {
        return execScript(serverName, script, SshExecOptions.defaults());
    }

    /**
     * 在指定具名服务器节点上执行多行脚本序列
     *
     * @param serverName 节点名称
     * @param script     多行脚本内容
     * @param options    参数选项
     * @return 强类型命令执行结果
     */
    public SshExecResult execScript(String serverName, String script, SshExecOptions options) {
        return execScript(resolveServerInfo(serverName), script, options);
    }

    /**
     * 在指定的目标服务器描述符上执行多行脚本序列
     *
     * @param serverInfo 目标描述符
     * @param script     多行脚本内容
     * @return 强类型命令执行结果
     */
    public SshExecResult execScript(SshServerInfo serverInfo, String script) {
        return execScript(serverInfo, script, SshExecOptions.defaults());
    }

    /**
     * 在指定的目标服务器描述符上执行多行脚本序列（携带扩展参数）
     *
     * @param serverInfo 目标描述符
     * @param script     多行脚本内容
     * @param options    参数选项
     * @return 强类型命令执行结果
     */
    public SshExecResult execScript(SshServerInfo serverInfo, String script, SshExecOptions options) {
        Assert.hasText(script, "script must not be blank");
        // 标准化多行脚本换行符：统一将 Windows \r\n 转换为 Unix \n，防御远端 Shell 报 $'\r': command not found
        String normalizedScript = script.replace("\r\n", "\n").replace("\r", "\n");
        return exec(serverInfo, normalizedScript, options);
    }

    // ==========================================
    // 3.1 异步多行脚本执行契约 (execScriptAsync)
    // ==========================================

    /**
     * 在上下文解析的目标服务器上异步执行多行脚本
     *
     * @param script 多行 Shell 脚本
     * @return 强类型命令执行结果异步凭据 (CompletableFuture)
     */
    public CompletableFuture<SshExecResult> execScriptAsync(String script) {
        return execScriptAsync(script, SshExecOptions.defaults());
    }

    /**
     * 在上下文解析的目标服务器上异步执行多行脚本（携带扩展配置）
     *
     * @param script  多行 Shell 脚本
     * @param options 扩展配置选项
     * @return 强类型命令执行结果异步凭据 (CompletableFuture)
     */
    public CompletableFuture<SshExecResult> execScriptAsync(String script, SshExecOptions options) {
        return execScriptAsync((SshServerInfo) null, script, options);
    }

    /**
     * 在指定具名服务器节点上异步执行多行脚本
     *
     * @param serverName 节点名称
     * @param script     多行 Shell 脚本
     * @return 强类型命令执行结果异步凭据 (CompletableFuture)
     */
    public CompletableFuture<SshExecResult> execScriptAsync(String serverName, String script) {
        return execScriptAsync(serverName, script, SshExecOptions.defaults());
    }

    /**
     * 在指定具名服务器节点上异步执行多行脚本（携带扩展配置）
     *
     * @param serverName 节点名称
     * @param script     多行 Shell 脚本
     * @param options    扩展配置选项
     * @return 强类型命令执行结果异步凭据 (CompletableFuture)
     */
    public CompletableFuture<SshExecResult> execScriptAsync(String serverName, String script, SshExecOptions options) {
        return execScriptAsync(resolveServerInfo(serverName), script, options);
    }

    /**
     * 在指定目标描述符服务器上异步执行多行脚本
     *
     * @param serverInfo 目标描述符
     * @param script     多行 Shell 脚本
     * @return 强类型命令执行结果异步凭据 (CompletableFuture)
     */
    public CompletableFuture<SshExecResult> execScriptAsync(SshServerInfo serverInfo, String script) {
        return execScriptAsync(serverInfo, script, SshExecOptions.defaults());
    }

    /**
     * 在指定目标描述符服务器上异步执行多行脚本（携带扩展配置，联动 Core 受管线程池）
     *
     * @param serverInfo 目标描述符
     * @param script     多行 Shell 脚本
     * @param options    扩展配置选项
     * @return 强类型命令执行结果异步凭据 (CompletableFuture)
     */
    public CompletableFuture<SshExecResult> execScriptAsync(SshServerInfo serverInfo, String script, SshExecOptions options) {
        SshServerInfo target = resolveServerInfo(serverInfo);
        SshExecOptions effectiveOptions = options != null ? options : SshExecOptions.defaults();
        Executor targetExecutor = getEffectiveExecutor();
        Supplier<SshExecResult> task = AsyncContext.wrapSupplier(() -> execScript(target, script, effectiveOptions));
        return CompletableFuture.supplyAsync(task, targetExecutor);
    }

    // ==========================================
    // 4. 瞬态单次任务隔离执行 (execOnce)
    // ==========================================

    /**
     * 瞬态隔离执行命令（执行完毕立即物理关闭会话，零池化缓存占用）
     *
     * @param serverInfo 目标描述符
     * @param command    待执行命令
     * @return 强类型命令执行结果
     */
    public SshExecResult execOnce(SshServerInfo serverInfo, String command) {
        return execOnce(serverInfo, command, SshExecOptions.defaults());
    }

    /**
     * 瞬态隔离执行命令（携带扩展参数，执行完毕立即物理关闭会话）
     *
     * @param serverInfo 目标描述符
     * @param command    待执行命令
     * @param options    参数选项
     * @return 强类型命令执行结果
     */
    public SshExecResult execOnce(SshServerInfo serverInfo, String command, SshExecOptions options) {
        SshServerInfo target = resolveServerInfo(serverInfo);
        Duration timeout = resolveDefaultTimeout(target);
        return sessionPool.executeOnce(target, session ->
                SshCommandExecutor.executeSync(session, command, options, timeout));
    }

    // ==========================================
    // 5. 底层原生会话逃逸舱 (execute)
    // ==========================================

    /**
     * 在池化会话上下文中执行底层原生操作
     *
     * @param callback 回调逻辑
     * @param <T>      返回值类型
     * @return 执行结果
     */
    public <T> T execute(SshSessionCallback<T> callback) {
        return execute((SshServerInfo) null, callback);
    }

    /**
     * 在指定目标描述符的池化会话上下文中执行底层原生操作
     *
     * @param serverInfo 目标描述符
     * @param callback   回调逻辑
     * @param <T>      返回值类型
     * @return 执行结果
     */
    public <T> T execute(SshServerInfo serverInfo, SshSessionCallback<T> callback) {
        return sessionPool.execute(resolveServerInfo(serverInfo), callback);
    }

    /**
     * 在指定目标描述符的池化会话上下文中执行底层原生操作（无返回值）
     *
     * @param serverInfo 目标描述符
     * @param action     执行逻辑
     */
    public void execute(SshServerInfo serverInfo, SshSessionAction action) {
        sessionPool.executeAction(resolveServerInfo(serverInfo), action);
    }

    // ==========================================
    // 6. 原生 Sudo 提权安全管道契约 (execSudo)
    // ==========================================

    /**
     * 在当前解析的目标服务器上以管理员提权身份执行命令（口令字符串）
     *
     * @param command      待执行命令
     * @param sudoPassword 提权口令
     * @return 强类型命令执行结果
     */
    public SshExecResult execSudo(String command, String sudoPassword) {
        return execSudo(command, sudoPassword, SshExecOptions.defaults());
    }

    /**
     * 在当前解析的目标服务器上以管理员提权身份执行命令（口令字符串 + 扩展配置）
     *
     * @param command      待执行命令
     * @param sudoPassword 提权口令
     * @param options      扩展配置选项
     * @return 强类型命令执行结果
     */
    public SshExecResult execSudo(String command, String sudoPassword, SshExecOptions options) {
        return execSudo((SshServerInfo) null, command, sudoPassword, options);
    }

    /**
     * 在当前解析的目标服务器上以管理员提权身份执行命令（字符数组口令，传输完毕就地抹零）
     *
     * @param command      待执行命令
     * @param sudoPassword 提权口令字符数组（内存擦除抹零守护）
     * @return 强类型命令执行结果
     */
    public SshExecResult execSudo(String command, char[] sudoPassword) {
        return execSudo(command, sudoPassword, SshExecOptions.defaults());
    }

    /**
     * 在当前解析的目标服务器上以管理员提权身份执行命令（字符数组口令 + 扩展配置）
     *
     * @param command      待执行命令
     * @param sudoPassword 提权口令字符数组（内存擦除抹零守护）
     * @param options      扩展配置选项
     * @return 强类型命令执行结果
     */
    public SshExecResult execSudo(String command, char[] sudoPassword, SshExecOptions options) {
        return execSudo((SshServerInfo) null, command, sudoPassword, options);
    }

    /**
     * 在指定具名服务器节点上以管理员提权身份执行命令
     *
     * @param serverName   节点名称
     * @param command      待执行命令
     * @param sudoPassword 提权口令
     * @return 强类型命令执行结果
     */
    public SshExecResult execSudo(String serverName, String command, String sudoPassword) {
        return execSudo(serverName, command, sudoPassword, SshExecOptions.defaults());
    }

    /**
     * 在指定具名服务器节点上以管理员提权身份执行命令（携带扩展配置）
     *
     * @param serverName   节点名称
     * @param command      待执行命令
     * @param sudoPassword 提权口令
     * @param options      扩展配置选项
     * @return 强类型命令执行结果
     */
    public SshExecResult execSudo(String serverName, String command, String sudoPassword, SshExecOptions options) {
        return execSudo(resolveServerInfo(serverName), command, sudoPassword, options);
    }

    /**
     * 在指定具名服务器节点上以管理员提权身份执行命令（字符数组口令）
     *
     * @param serverName   节点名称
     * @param command      待执行命令
     * @param sudoPassword 提权口令字符数组（内存擦除抹零守护）
     * @return 强类型命令执行结果
     */
    public SshExecResult execSudo(String serverName, String command, char[] sudoPassword) {
        return execSudo(serverName, command, sudoPassword, SshExecOptions.defaults());
    }

    /**
     * 在指定具名服务器节点上以管理员提权身份执行命令（字符数组口令 + 扩展配置）
     *
     * @param serverName   节点名称
     * @param command      待执行命令
     * @param sudoPassword 提权口令字符数组（内存擦除抹零守护）
     * @param options      扩展配置选项
     * @return 强类型命令执行结果
     */
    public SshExecResult execSudo(String serverName, String command, char[] sudoPassword, SshExecOptions options) {
        return execSudo(resolveServerInfo(serverName), command, sudoPassword, options);
    }

    /**
     * 在指定目标描述符服务器上以管理员提权身份执行命令（字符串口令）
     *
     * @param serverInfo   目标描述符
     * @param command      待执行命令
     * @param sudoPassword 提权口令
     * @return 强类型命令执行结果
     */
    public SshExecResult execSudo(SshServerInfo serverInfo, String command, String sudoPassword) {
        return execSudo(serverInfo, command, sudoPassword, SshExecOptions.defaults());
    }

    /**
     * 在指定目标描述符服务器上以管理员提权身份执行命令（字符串口令 + 扩展配置）
     *
     * @param serverInfo   目标描述符
     * @param command      待执行命令
     * @param sudoPassword 提权口令
     * @param options      扩展配置选项
     * @return 强类型命令执行结果
     */
    public SshExecResult execSudo(SshServerInfo serverInfo, String command, String sudoPassword, SshExecOptions options) {
        char[] chars = sudoPassword != null ? sudoPassword.toCharArray() : null;
        return execSudo(serverInfo, command, chars, options);
    }

    /**
     * 在指定目标描述符服务器上以管理员提权身份执行命令（字符数组口令）
     *
     * @param serverInfo   目标描述符
     * @param command      待执行命令
     * @param sudoPassword 提权口令字符数组（内存擦除抹零守护）
     * @return 强类型命令执行结果
     */
    public SshExecResult execSudo(SshServerInfo serverInfo, String command, char[] sudoPassword) {
        return execSudo(serverInfo, command, sudoPassword, SshExecOptions.defaults());
    }

    /**
     * 在指定目标描述符服务器上以管理员提权身份执行命令（字符数组口令 + 扩展配置）
     *
     * @param serverInfo   目标描述符
     * @param command      待执行命令
     * @param sudoPassword 提权口令字符数组（内存擦除抹零守护）
     * @param options      扩展配置选项
     * @return 强类型命令执行结果
     */
    public SshExecResult execSudo(SshServerInfo serverInfo, String command, char[] sudoPassword, SshExecOptions options) {
        SshServerInfo target = resolveServerInfo(serverInfo);
        Duration timeout = resolveDefaultTimeout(target);
        return sessionPool.execute(target, session ->
                SshCommandExecutor.executeSudo(session, command, sudoPassword, options, timeout));
    }

    /**
     * 在指定目标描述符服务器上瞬态隔离执行 Sudo 提权命令（用后即断，零池化残留）
     *
     * @param serverInfo   目标描述符
     * @param command      待执行命令
     * @param sudoPassword 提权口令
     * @return 强类型命令执行结果
     */
    public SshExecResult execSudoOnce(SshServerInfo serverInfo, String command, String sudoPassword) {
        return execSudoOnce(serverInfo, command, sudoPassword, SshExecOptions.defaults());
    }

    /**
     * 在指定目标描述符服务器上瞬态隔离执行 Sudo 提权命令（携带扩展配置）
     *
     * @param serverInfo   目标描述符
     * @param command      待执行命令
     * @param sudoPassword 提权口令
     * @param options      扩展配置选项
     * @return 强类型命令执行结果
     */
    public SshExecResult execSudoOnce(SshServerInfo serverInfo, String command, String sudoPassword, SshExecOptions options) {
        char[] chars = sudoPassword != null ? sudoPassword.toCharArray() : null;
        return execSudoOnce(serverInfo, command, chars, options);
    }

    /**
     * 在指定目标描述符服务器上瞬态隔离执行 Sudo 提权命令（字符数组口令）
     *
     * @param serverInfo   目标描述符
     * @param command      待执行命令
     * @param sudoPassword 提权口令字符数组（内存擦除抹零守护）
     * @return 强类型命令执行结果
     */
    public SshExecResult execSudoOnce(SshServerInfo serverInfo, String command, char[] sudoPassword) {
        return execSudoOnce(serverInfo, command, sudoPassword, SshExecOptions.defaults());
    }

    /**
     * 在指定目标描述符服务器上瞬态隔离执行 Sudo 提权命令（字符数组口令 + 扩展配置）
     *
     * @param serverInfo   目标描述符
     * @param command      待执行命令
     * @param sudoPassword 提权口令字符数组（内存擦除抹零守护）
     * @param options      扩展配置选项
     * @return 强类型命令执行结果
     */
    public SshExecResult execSudoOnce(SshServerInfo serverInfo, String command, char[] sudoPassword, SshExecOptions options) {
        SshServerInfo target = resolveServerInfo(serverInfo);
        Duration timeout = resolveDefaultTimeout(target);
        return sessionPool.executeOnce(target, session ->
                SshCommandExecutor.executeSudo(session, command, sudoPassword, options, timeout));
    }

    // ==========================================
    // 7. 集群批量并发巡检契约 (execBatch)
    // ==========================================

    /**
     * 在多个目标服务器上并发执行命令（开箱即用，透传四维上下文）
     *
     * @param servers 目标服务器集合
     * @param command 待巡检执行命令
     * @return 每个目标主机的执行结果映射字典
     */
    public Map<SshServerInfo, SshExecResult> execBatch(Collection<SshServerInfo> servers, String command) {
        return execBatch(servers, command, SshExecOptions.defaults());
    }

    /**
     * 在多个目标服务器上并发执行命令（携带扩展参数，具备节点异常隔离能力）
     *
     * @param servers 目标服务器集合
     * @param command 待巡检执行命令
     * @param options 扩展配置选项
     * @return 每个目标主机的执行结果映射字典
     */
    public Map<SshServerInfo, SshExecResult> execBatch(Collection<SshServerInfo> servers,
                                                       String command,
                                                       SshExecOptions options) {
        SshExecOptions effectiveOptions = options != null ? options : SshExecOptions.defaults();
        return executeBatchInternal(servers, command, "执行", server -> exec(server, command, effectiveOptions));
    }

    /**
     * 在多个具名节点上并发执行命令
     *
     * @param serverNames 节点名称集合
     * @param command     待巡检执行命令
     * @return 节点名称与执行结果映射字典
     */
    public Map<String, SshExecResult> execBatchNamed(Collection<String> serverNames, String command) {
        return execBatchNamed(serverNames, command, SshExecOptions.defaults());
    }

    /**
     * 在多个具名节点上并发执行命令（携带扩展参数）
     *
     * @param serverNames 节点名称集合
     * @param command     待巡检执行命令
     * @param options     扩展配置选项
     * @return 节点名称与执行结果映射字典
     */
    public Map<String, SshExecResult> execBatchNamed(Collection<String> serverNames,
                                                     String command,
                                                     SshExecOptions options) {
        if (serverNames == null || serverNames.isEmpty()) {
            return Collections.emptyMap();
        }
        Map<String, SshServerInfo> nameToServer = new LinkedHashMap<>();
        for (String name : serverNames) {
            if (name != null) {
                nameToServer.put(name, resolveServerInfo(name));
            }
        }
        Map<SshServerInfo, SshExecResult> batchResult = execBatch(nameToServer.values(), command, options);
        Map<String, SshExecResult> resultMap = new LinkedHashMap<>();
        for (Map.Entry<String, SshServerInfo> entry : nameToServer.entrySet()) {
            resultMap.put(entry.getKey(), batchResult.get(entry.getValue()));
        }
        return resultMap;
    }

    /**
     * 在多个目标服务器上并发执行 Sudo 提权命令（字符串口令）
     *
     * @param servers      目标服务器集合
     * @param command      待执行提权命令
     * @param sudoPassword 提权口令
     * @return 每个主机的执行结果映射字典
     */
    public Map<SshServerInfo, SshExecResult> execSudoBatch(Collection<SshServerInfo> servers,
                                                           String command,
                                                           String sudoPassword) {
        return execSudoBatch(servers, command, sudoPassword, SshExecOptions.defaults());
    }

    /**
     * 在多个目标服务器上并发执行 Sudo 提权命令（字符串口令 + 扩展配置）
     *
     * @param servers      目标服务器集合
     * @param command      待执行提权命令
     * @param sudoPassword 提权口令
     * @param options      扩展配置选项
     * @return 每个主机的执行结果映射字典
     */
    public Map<SshServerInfo, SshExecResult> execSudoBatch(Collection<SshServerInfo> servers,
                                                           String command,
                                                           String sudoPassword,
                                                           SshExecOptions options) {
        SshExecOptions effectiveOptions = options != null ? options : SshExecOptions.defaults();
        return executeBatchInternal(servers, command, "Sudo", server -> execSudo(server, command, sudoPassword, effectiveOptions));
    }

    /**
     * 在多个目标服务器上并发执行 Sudo 提权命令（字符数组口令）
     *
     * @param servers      目标服务器集合
     * @param command      待执行提权命令
     * @param sudoPassword 提权口令字符数组
     * @return 每个主机的执行结果映射字典
     */
    public Map<SshServerInfo, SshExecResult> execSudoBatch(Collection<SshServerInfo> servers,
                                                           String command,
                                                           char[] sudoPassword) {
        return execSudoBatch(servers, command, sudoPassword, SshExecOptions.defaults());
    }

    /**
     * 在多个目标服务器上并发执行 Sudo 提权命令（字符数组口令 + 扩展配置）
     *
     * @param servers      目标服务器集合
     * @param command      待执行提权命令
     * @param sudoPassword 提权口令字符数组
     * @param options      扩展配置选项
     * @return 每个主机的执行结果映射字典
     */
    public Map<SshServerInfo, SshExecResult> execSudoBatch(Collection<SshServerInfo> servers,
                                                           String command,
                                                           char[] sudoPassword,
                                                           SshExecOptions options) {
        SshExecOptions effectiveOptions = options != null ? options : SshExecOptions.defaults();
        try {
            return executeBatchInternal(servers, command, "Sudo", server -> {
                char[] copy = sudoPassword != null ? sudoPassword.clone() : null;
                return execSudo(server, command, copy, effectiveOptions);
            });
        } finally {
            if (sudoPassword != null) {
                java.util.Arrays.fill(sudoPassword, '\0');
            }
        }
    }

    private Map<SshServerInfo, SshExecResult> executeBatchInternal(
            Collection<SshServerInfo> servers,
            String command,
            String operationName,
            java.util.function.Function<SshServerInfo, SshExecResult> taskFunction) {
        if (servers == null || servers.isEmpty()) {
            return Collections.emptyMap();
        }
        Assert.hasText(command, "command must not be blank");

        Executor targetExecutor = getEffectiveExecutor();
        Map<SshServerInfo, CompletableFuture<SshExecResult>> futures = new LinkedHashMap<>();

        for (SshServerInfo server : servers) {
            if (server == null) {
                continue;
            }
            Supplier<SshExecResult> task = AsyncContext.wrapSupplier(() -> {
                long start = System.currentTimeMillis();
                try {
                    return taskFunction.apply(server);
                } catch (Exception e) {
                    log.warn("ateng-boot-ssh: 批量 {} 节点异常隔离 [{}]: {}", operationName, server.toSessionKey(), e.getMessage());
                    Duration duration = Duration.ofMillis(System.currentTimeMillis() - start);
                    String errorMsg = e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName();
                    return SshExecResult.builder()
                            .exitCode(-1)
                            .stderr(errorMsg)
                            .duration(duration)
                            .build();
                }
            });

            futures.put(server, CompletableFuture.supplyAsync(task, targetExecutor));
        }

        CompletableFuture.allOf(futures.values().toArray(new CompletableFuture[0])).join();

        Map<SshServerInfo, SshExecResult> resultMap = new LinkedHashMap<>();
        for (Map.Entry<SshServerInfo, CompletableFuture<SshExecResult>> entry : futures.entrySet()) {
            try {
                resultMap.put(entry.getKey(), entry.getValue().join());
            } catch (Exception e) {
                resultMap.put(entry.getKey(), SshExecResult.builder()
                        .exitCode(-1)
                        .stderr(e.getMessage() != null ? e.getMessage() : "Unknown execution error")
                        .build());
            }
        }
        return resultMap;
    }

    @Override
    public void destroy() {
        close();
    }

    @Override
    public void close() {
        if (internalExecutor != null) {
            internalExecutor.shutdown();
        }
    }

    // ==========================================
    // 6. 本地端口转发受管隧道契约 (openLocalTunnel)
    // ==========================================

    /**
     * 在上下文解析的目标服务器上开启本地端口转发隧道
     *
     * @param localPort  本地监听绑定端口（传 0 由操作系统动态分配可用随机端口）
     * @param remoteHost 目标远端服务主机名或 IP
     * @param remotePort 目标远端服务端口
     * @return 实现了 AutoCloseable 的受管隧道句柄
     */
    public SshTunnelHandle openLocalTunnel(int localPort, String remoteHost, int remotePort) {
        return openLocalTunnel((SshServerInfo) null, localPort, remoteHost, remotePort);
    }

    /**
     * 在指定具名服务器节点上开启本地端口转发隧道
     *
     * @param serverName 节点名称
     * @param localPort  本地监听绑定端口（传 0 由操作系统动态分配可用随机端口）
     * @param remoteHost 目标远端服务主机名或 IP
     * @param remotePort 目标远端服务端口
     * @return 实现了 AutoCloseable 的受管隧道句柄
     */
    public SshTunnelHandle openLocalTunnel(String serverName, int localPort, String remoteHost, int remotePort) {
        return openLocalTunnel(resolveServerInfo(serverName), localPort, remoteHost, remotePort);
    }

    /**
     * 在指定的目标服务器描述符上开启本地端口转发隧道
     *
     * @param serverInfo 目标描述符（作为端口转发中继/跳板节点）
     * @param localPort  本地监听绑定端口（传 0 由操作系统动态分配可用随机端口）
     * @param remoteHost 目标远端服务主机名或 IP
     * @param remotePort 目标远端服务端口
     * @return 实现了 AutoCloseable 的受管隧道句柄
     */
    public SshTunnelHandle openLocalTunnel(SshServerInfo serverInfo, int localPort, String remoteHost, int remotePort) {
        Assert.isTrue(localPort >= 0 && localPort <= 65535, "localPort must be between 0 and 65535");
        Assert.hasText(remoteHost, "remoteHost must not be blank");
        Assert.isTrue(remotePort > 0 && remotePort <= 65535, "remotePort must be between 1 and 65535");

        SshdSocketAddress local = new SshdSocketAddress("127.0.0.1", localPort);
        SshdSocketAddress remote = new SshdSocketAddress(remoteHost.trim(), remotePort);
        return openLocalTunnel(serverInfo, local, remote);
    }

    /**
     * 在指定的目标服务器描述符上开启本地端口转发隧道（支持指定本地监听地址与远端套接字）
     *
     * @param serverInfo 目标描述符
     * @param local      本地监听网络地址
     * @param remote     远端目标网络地址
     * @return 实现了 AutoCloseable 的受管隧道句柄
     */
    public SshTunnelHandle openLocalTunnel(SshServerInfo serverInfo, SshdSocketAddress local, SshdSocketAddress remote) {
        Assert.notNull(local, "Local socket address must not be null");
        Assert.notNull(remote, "Remote socket address must not be null");

        SshServerInfo target = resolveServerInfo(serverInfo);
        ClientSession session = sessionPool.acquire(target);

        try {
            ExplicitPortForwardingTracker tracker = session.createLocalPortForwardingTracker(local, remote);
            SshdSocketAddress bound = tracker.getBoundAddress();
            int boundPort = bound.getPort();
            String boundHost = bound.getHostName();

            SshTunnelHandle handle = new SshTunnelHandle(
                    target,
                    tracker,
                    boundHost,
                    boundPort,
                    remote.getHostName(),
                    remote.getPort(),
                    tunnel -> {
                        if (lifecycleManager != null) {
                            lifecycleManager.unregisterTunnel(tunnel);
                        }
                    }
            );

            // 注册到生命周期管理器，保障停机时优先自动注销
            if (lifecycleManager != null) {
                lifecycleManager.registerTunnel(handle);
            }

            log.info("ateng-boot-ssh: 本地端口转发隧道已开启 [{}:{} -> {}:{} via {}]",
                    boundHost, boundPort, remote.getHostName(), remote.getPort(), target.toSessionKey());
            return handle;
        } catch (IOException e) {
            throw new SshOperationException(SshResultCode.TUNNEL_FAILED,
                    "开启本地端口转发失败 [" + local + " -> " + remote + " via " + target.toSessionKey() + "]: " + e.getMessage(), e);
        }
    }

    // ==========================================
    // 7. 交互式 PTY 伪终端受管句柄契约 (openShell)
    // ==========================================

    /**
     * 在上下文解析的目标服务器上开启默认交互式 PTY 伪终端
     *
     * @return 实现了 AutoCloseable 的受管终端句柄
     */
    public SshShellHandle openShell() {
        return openShell((SshServerInfo) null, (SshShellOptions) null);
    }

    /**
     * 在上下文解析的目标服务器上开启指定规格的交互式 PTY 伪终端
     *
     * @param options 终端规格配置（行列尺寸、仿真类型、环境变量等）
     * @return 实现了 AutoCloseable 的受管终端句柄
     */
    public SshShellHandle openShell(SshShellOptions options) {
        return openShell((SshServerInfo) null, options);
    }

    /**
     * 在指定具名服务器节点上开启默认交互式 PTY 伪终端
     *
     * @param serverName 节点名称
     * @return 实现了 AutoCloseable 的受管终端句柄
     */
    public SshShellHandle openShell(String serverName) {
        return openShell(resolveServerInfo(serverName), (SshShellOptions) null);
    }

    /**
     * 在指定具名服务器节点上开启指定规格的交互式 PTY 伪终端
     *
     * @param serverName 节点名称
     * @param options    终端规格配置
     * @return 实现了 AutoCloseable 的受管终端句柄
     */
    public SshShellHandle openShell(String serverName, SshShellOptions options) {
        return openShell(resolveServerInfo(serverName), options);
    }

    /**
     * 在指定的目标服务器描述符上开启默认交互式 PTY 伪终端
     *
     * @param serverInfo 目标描述符
     * @return 实现了 AutoCloseable 的受管终端句柄
     */
    public SshShellHandle openShell(SshServerInfo serverInfo) {
        return openShell(serverInfo, (SshShellOptions) null);
    }

    /**
     * 在指定的目标服务器描述符上开启指定规格的交互式 PTY 伪终端
     *
     * @param serverInfo 目标描述符
     * @param options    终端规格配置
     * @return 实现了 AutoCloseable 的受管终端句柄
     */
    public SshShellHandle openShell(SshServerInfo serverInfo, SshShellOptions options) {
        SshServerInfo target = resolveServerInfo(serverInfo);
        SshShellOptions effectiveOptions = options != null ? options : SshShellOptions.defaults();

        ClientSession session = sessionPool.acquire(target);
        ChannelShell channel = null;
        try {
            channel = session.createShellChannel();
            effectiveOptions.applyTo(channel);

            SshShellHandle handle = new SshShellHandle(
                    target,
                    channel,
                    effectiveOptions,
                    shell -> {
                        if (lifecycleManager != null) {
                            lifecycleManager.unregisterShell(shell);
                        }
                    }
            );

            // 开启通道并等待服务端确认
            channel.open().verify(effectiveOptions.getTimeout());

            // 注册到生命周期管理器，保障停机时优先自动排空
            if (lifecycleManager != null) {
                lifecycleManager.registerShell(handle);
            }

            log.info("ateng-boot-ssh: 交互式 PTY 伪终端已开启 [via {}, term={}, size={}x{}]",
                    target.toSessionKey(), effectiveOptions.getTerm(), effectiveOptions.getCols(), effectiveOptions.getRows());
            return handle;
        } catch (Exception e) {
            if (channel != null) {
                try {
                    channel.close(true);
                } catch (Exception ignored) {
                }
            }
            throw new SshOperationException(SshResultCode.SHELL_OPEN_FAILED,
                    "开启 SSH 交互式终端失败 [" + target.toSessionKey() + "]: " + e.getMessage(), e);
        }
    }

    // ==========================================
    // 10. 轻量无害连通性健康探测契约 (testConnection)
    // ==========================================

    /**
     * 探测当前上下文或缺省服务器节点的网络与身份认证连通性
     * <p>
     * 仅在底层完成 TCP 三次握手与 SSH 凭据身份鉴权握手，100% 绝不开辟任何 Exec/Shell/SFTP 业务通道。
     * 瞬态验证完毕后立即确定性释放底层网络连接，安全无副作用。
     * </p>
     *
     * @return 节点是否连通可用
     */
    public boolean testConnection() {
        return testConnection((SshServerInfo) null, (Duration) null);
    }

    /**
     * 探测当前上下文或缺省服务器节点的网络与身份认证连通性（指定超时阈值）
     *
     * @param timeout 探测超时时间
     * @return 节点是否连通可用
     */
    public boolean testConnection(Duration timeout) {
        return testConnection((SshServerInfo) null, timeout);
    }

    /**
     * 探测指定具名服务器节点的网络与身份认证连通性
     *
     * @param serverName 节点名称
     * @return 节点是否连通可用
     */
    public boolean testConnection(String serverName) {
        return testConnection(serverName, (Duration) null);
    }

    /**
     * 探测指定具名服务器节点的网络与身份认证连通性（指定超时阈值）
     *
     * @param serverName 节点名称
     * @param timeout    探测超时时间
     * @return 节点是否连通可用
     */
    public boolean testConnection(String serverName, Duration timeout) {
        return testConnection(resolveServerInfo(serverName), timeout);
    }

    /**
     * 探测指定目标服务器描述符的网络与身份认证连通性
     *
     * @param serverInfo 目标描述符
     * @return 节点是否连通可用
     */
    public boolean testConnection(SshServerInfo serverInfo) {
        return testConnection(serverInfo, (Duration) null);
    }

    /**
     * 探测指定目标服务器描述符的网络与身份认证连通性（指定超时阈值）
     *
     * @param serverInfo 目标描述符
     * @param timeout    探测超时时间
     * @return 节点是否连通可用
     */
    public boolean testConnection(SshServerInfo serverInfo, Duration timeout) {
        try {
            testConnectionOrThrow(serverInfo, timeout);
            return true;
        } catch (Exception e) {
            log.debug("ateng-boot-ssh: 连通性健康探测失败: {}", e.getMessage());
            return false;
        }
    }

    /**
     * 探测当前上下文或缺省服务器节点的网络与身份认证连通性，不可达时抛出异常
     *
     * @throws SshOperationException 节点不可达或凭据认证失败
     */
    public void testConnectionOrThrow() {
        testConnectionOrThrow((SshServerInfo) null, (Duration) null);
    }

    /**
     * 探测当前上下文或缺省服务器节点的网络与身份认证连通性（指定超时阈值），不可达时抛出异常
     *
     * @param timeout 探测超时时间
     * @throws SshOperationException 节点不可达或凭据认证失败
     */
    public void testConnectionOrThrow(Duration timeout) {
        testConnectionOrThrow((SshServerInfo) null, timeout);
    }

    /**
     * 探测指定具名服务器节点的网络与身份认证连通性，不可达时抛出异常
     *
     * @param serverName 节点名称
     * @throws SshOperationException 节点不可达或凭据认证失败
     */
    public void testConnectionOrThrow(String serverName) {
        testConnectionOrThrow(serverName, (Duration) null);
    }

    /**
     * 探测指定具名服务器节点的网络与身份认证连通性（指定超时阈值），不可达时抛出异常
     *
     * @param serverName 节点名称
     * @param timeout    探测超时时间
     * @throws SshOperationException 节点不可达或凭据认证失败
     */
    public void testConnectionOrThrow(String serverName, Duration timeout) {
        testConnectionOrThrow(resolveServerInfo(serverName), timeout);
    }

    /**
     * 探测指定目标服务器描述符的网络与身份认证连通性，不可达时抛出异常
     *
     * @param serverInfo 目标描述符
     * @throws SshOperationException 节点不可达或凭据认证失败
     */
    public void testConnectionOrThrow(SshServerInfo serverInfo) {
        testConnectionOrThrow(serverInfo, (Duration) null);
    }

    /**
     * 探测指定目标服务器描述符的网络与身份认证连通性（指定超时阈值），不可达时抛出异常
     *
     * @param serverInfo 目标描述符
     * @param timeout    探测超时时间
     * @throws SshOperationException 节点不可达或凭据认证失败
     */
    public void testConnectionOrThrow(SshServerInfo serverInfo, Duration timeout) {
        SshServerInfo target = resolveServerInfo(serverInfo);
        if (timeout != null && !timeout.isZero() && !timeout.isNegative()) {
            target = target.toBuilder().connectTimeout(timeout).authTimeout(timeout).build();
        }
        SshServerInfo effectiveTarget = target;
        try {
            sessionPool.executeOnce(effectiveTarget, session -> {
                if (!session.isOpen()) {
                    throw new SshOperationException(SshResultCode.PROBE_FAILED,
                            "SSH 探测会话未能开启就绪 [" + effectiveTarget.toSessionKey() + "]");
                }
                return true;
            });
        } catch (SshOperationException e) {
            throw new SshOperationException(SshResultCode.PROBE_FAILED,
                    "SSH 节点连通性探测失败 [" + effectiveTarget.toSessionKey() + "]: " + e.getMessage(), e);
        } catch (Exception e) {
            throw new SshOperationException(SshResultCode.PROBE_FAILED,
                    "SSH 节点连通性探测失败 [" + effectiveTarget.toSessionKey() + "]: " + e.getMessage(), e);
        }
    }

    private Executor getEffectiveExecutor() {
        if (this.executor != null) {
            return this.executor;
        }
        if (this.internalExecutor == null) {
            synchronized (this) {
                if (this.internalExecutor == null) {
                    ThreadPoolTaskExecutor exec = new ThreadPoolTaskExecutor();
                    exec.setCorePoolSize(Math.max(2, Runtime.getRuntime().availableProcessors()));
                    exec.setMaxPoolSize(Math.max(4, Runtime.getRuntime().availableProcessors() * 2));
                    exec.setQueueCapacity(200);
                    exec.setThreadNamePrefix("ssh-batch-");
                    exec.setWaitForTasksToCompleteOnShutdown(true);
                    exec.setAwaitTerminationSeconds(5);
                    exec.initialize();
                    this.internalExecutor = exec;
                }
            }
        }
        return this.internalExecutor;
    }

    // ==========================================
    // 内部四位一体路由解析辅助
    // ==========================================

    /**
     * 四位一体目标服务器连接描述符解析核心逻辑：
     * 1. 显式传入值对象优先；
     * 2. 线程上下文 SshContextHolder.getServerInfo() 其次；
     * 3. 线程上下文 SshContextHolder.getServer() 命名寻址；
     * 4. 保底降级至注册表 default 主节点。
     *
     * @param explicitInfo 显式传入的值对象
     * @return 最终确定的目标连接描述符
     */
    public SshServerInfo resolveServerInfo(SshServerInfo explicitInfo) {
        if (explicitInfo != null) {
            return explicitInfo;
        }

        SshServerInfo contextInfo = SshContextHolder.getServerInfo();
        if (contextInfo != null) {
            return contextInfo;
        }

        String contextName = SshContextHolder.getServer();
        if (contextName != null && !contextName.isBlank()) {
            return clientRegistry.getServer(contextName);
        }

        return clientRegistry.getDefaultServer();
    }

    /**
     * 根据具名节点名称解析目标描述符
     *
     * @param serverName 节点名称
     * @return 目标连接描述符
     */
    public SshServerInfo resolveServerInfo(String serverName) {
        if (serverName != null && !serverName.isBlank()) {
            return clientRegistry.getServer(serverName);
        }
        return resolveServerInfo((SshServerInfo) null);
    }

    private Duration resolveDefaultTimeout(SshServerInfo target) {
        if (target != null && target.getCommandTimeout() != null && !target.getCommandTimeout().isZero()) {
            return target.getCommandTimeout();
        }
        if (properties.getCommandTimeout() != null && !properties.getCommandTimeout().isZero()) {
            return properties.getCommandTimeout();
        }
        return Duration.ofSeconds(60);
    }
}
