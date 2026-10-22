package io.github.atengk.boot.ssh.template;

import io.github.atengk.boot.ssh.context.SshContextHolder;
import io.github.atengk.boot.ssh.core.SshCommandExecutor;
import io.github.atengk.boot.ssh.model.SshExecOptions;
import io.github.atengk.boot.ssh.model.SshExecResult;
import io.github.atengk.boot.ssh.model.SshServerInfo;
import io.github.atengk.boot.ssh.pool.SshSessionAction;
import io.github.atengk.boot.ssh.pool.SshSessionCallback;
import io.github.atengk.boot.ssh.pool.SshSessionPool;
import io.github.atengk.boot.ssh.properties.SshProperties;
import io.github.atengk.boot.ssh.registry.SshClientRegistry;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;
import org.springframework.util.Assert;

import java.time.Duration;
import java.util.function.Consumer;

/**
 * SSH 核心操作门面 (SshTemplate)
 * <p>
 * 1. 深度融通四位一体路由：透明支持缺省节点、YAML 命名注册表、线程上下文 (SshContextHolder) 与 CMDB 动态直连描述符 (SshServerInfo)；<br>
 * 2. 对称涵盖同步短命令 (exec)、长任务实时按行流式消费 (execStream)、多行复杂脚本管道 (execScript) 与瞬态单次任务隔离 (execOnce)；<br>
 * 3. 底层依托受管会话池 (SshSessionPool) 与孤儿进程信号主动防卫机制，保障高并发生产环境下的极致性能与资源确定性释放。
 * </p>
 *
 * @author Ateng
 * @since 2026-10-23
 */
@Slf4j
public class SshTemplate {

    @Getter
    private final SshSessionPool sessionPool;

    @Getter
    private final SshClientRegistry clientRegistry;

    @Getter
    private final SshProperties properties;

    public SshTemplate(SshSessionPool sessionPool,
                       SshClientRegistry clientRegistry,
                       SshProperties properties) {
        Assert.notNull(sessionPool, "SshSessionPool must not be null");
        Assert.notNull(clientRegistry, "SshClientRegistry must not be null");
        this.sessionPool = sessionPool;
        this.clientRegistry = clientRegistry;
        this.properties = properties != null ? properties : new SshProperties();
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
        // 多行脚本直接交由底层 Exec 管道处理
        return exec(serverInfo, script, options);
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
