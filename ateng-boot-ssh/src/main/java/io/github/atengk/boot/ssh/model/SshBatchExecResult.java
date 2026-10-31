package io.github.atengk.boot.ssh.model;

import io.github.atengk.boot.ssh.exception.SshOperationException;
import io.github.atengk.boot.ssh.exception.SshResultCode;
import lombok.Builder;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.ToString;

import java.time.Duration;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;

/**
 * SSH 集群多节点批量巡检强类型聚合回执
 * <p>
 * 封装总节点数、成功数、失败数、总耗时、各节点执行结果与异常记录，
 * 并提供 {@link #isAllSuccess()} 与 {@link #getFailedServers()} 等断言能力。
 * </p>
 *
 * @author Ateng
 * @since 2026-10-31
 */
@Getter
@ToString
@EqualsAndHashCode
@Builder(toBuilder = true)
public final class SshBatchExecResult {

    /**
     * 目标节点总数
     */
    private final int totalCount;

    /**
     * 成功执行节点总数（exitCode == 0 且未发生未捕获异常）
     */
    private final int successCount;

    /**
     * 执行失败节点总数（exitCode != 0 或发生未捕获异常）
     */
    private final int failureCount;

    /**
     * 批量执行总耗时跨度
     */
    @Builder.Default
    private final Duration duration = Duration.ZERO;

    /**
     * 各目标服务器执行结果字典
     */
    @Builder.Default
    private final Map<SshServerInfo, SshExecResult> results = Collections.emptyMap();

    /**
     * 各目标服务器异常字典（若发生异常则记录在此）
     */
    @Builder.Default
    private final Map<SshServerInfo, Throwable> errors = Collections.emptyMap();

    /**
     * 以注册节点名称为 Key 的执行结果字典（当通过具名节点批量执行时自动填充）
     */
    private final Map<String, SshExecResult> namedResults;

    /**
     * 是否所有节点全部执行成功
     *
     * @return 若 failureCount == 0 且 successCount == totalCount 且 errors 为空则返回 true
     */
    public boolean isAllSuccess() {
        return failureCount == 0 && successCount == totalCount && errors.isEmpty();
    }

    /**
     * 获取所有执行失败的目标服务器集合
     *
     * @return 失败服务器集合（不可变）
     */
    public Set<SshServerInfo> getFailedServers() {
        Set<SshServerInfo> failed = new LinkedHashSet<>(errors.keySet());
        for (Map.Entry<SshServerInfo, SshExecResult> entry : results.entrySet()) {
            if (!entry.getValue().isSuccess()) {
                failed.add(entry.getKey());
            }
        }
        return Collections.unmodifiableSet(failed);
    }

    /**
     * 获取所有执行成功的目标服务器集合
     *
     * @return 成功服务器集合（不可变）
     */
    public Set<SshServerInfo> getSuccessServers() {
        Set<SshServerInfo> success = new LinkedHashSet<>();
        for (Map.Entry<SshServerInfo, SshExecResult> entry : results.entrySet()) {
            if (entry.getValue().isSuccess() && !errors.containsKey(entry.getKey())) {
                success.add(entry.getKey());
            }
        }
        return Collections.unmodifiableSet(success);
    }

    /**
     * 获取指定服务器的执行结果
     *
     * @param server 目标服务器
     * @return 执行结果（若不存在则返回 null）
     */
    public SshExecResult getResult(SshServerInfo server) {
        return results.get(server);
    }

    /**
     * 获取指定服务器的异常对象
     *
     * @param server 目标服务器
     * @return 异常对象（若无异常则返回 null）
     */
    public Throwable getError(SshServerInfo server) {
        return errors.get(server);
    }

    /**
     * 获取以服务器标识（host:port 或 alias）为 Key 的执行结果字典
     *
     * @return 节点名称与结果映射字典
     */
    public Map<String, SshExecResult> getNamedResults() {
        if (namedResults != null) {
            return namedResults;
        }
        if (results.isEmpty()) {
            return Collections.emptyMap();
        }
        Map<String, SshExecResult> map = new LinkedHashMap<>(results.size());
        for (Map.Entry<SshServerInfo, SshExecResult> entry : results.entrySet()) {
            SshServerInfo server = entry.getKey();
            map.put(server.toSessionKey(), entry.getValue());
        }
        return Collections.unmodifiableMap(map);
    }

    /**
     * 严格断言全量成功；若存在任一失败节点则抛出业务异常
     *
     * @return 当前回执自身
     * @throws SshOperationException 当存在失败节点时
     */
    public SshBatchExecResult assertAllSuccess() {
        if (!isAllSuccess()) {
            throw new SshOperationException(SshResultCode.BATCH_EXEC_FAILED,
                    "SSH集群批量巡检执行未完全成功: 总计 " + totalCount + " 节点, 成功 " + successCount + ", 失败 " + failureCount);
        }
        return this;
    }
}
