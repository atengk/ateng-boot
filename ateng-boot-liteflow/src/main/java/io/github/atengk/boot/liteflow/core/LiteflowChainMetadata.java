package io.github.atengk.boot.liteflow.core;

import java.io.Serializable;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * LiteFlow 运行时规则链拓扑与元数据不可变快照
 * <p>
 * 承载规则链唯一标识、路由命名空间、当前生效的主体 EL 与路由条件 EL、
 * 引用的业务组件节点清单以及历史版本快照深度，供中后台规则管控台在线自省。
 * </p>
 *
 * @param chainId              规则链唯一标识
 * @param namespace            所属决策路由命名空间（默认 {@code "default"}）
 * @param bodyEl               当前生效的主体 EL 表达式
 * @param routeEl              决策路由条件 EL 表达式（普通规则链为 {@code null}）
 * @param routeChain           是否为决策路由链
 * @param referencedNodeIds    该链引用的组件节点 ID 有序集合
 * @param historySnapshotCount 当前保留的历史快照数量
 * @param historySnapshots     历史版本快照 EL 列表（按时间倒序）
 * @author Ateng
 * @since 2026-11-21
 */
public record LiteflowChainMetadata(
        String chainId,
        String namespace,
        String bodyEl,
        String routeEl,
        boolean routeChain,
        Set<String> referencedNodeIds,
        int historySnapshotCount,
        List<String> historySnapshots
) implements Serializable {

    private static final long serialVersionUID = 1L;

    public LiteflowChainMetadata {
        referencedNodeIds = (referencedNodeIds != null && !referencedNodeIds.isEmpty())
                ? Collections.unmodifiableSet(new LinkedHashSet<>(referencedNodeIds))
                : Collections.emptySet();
        historySnapshots = (historySnapshots != null && !historySnapshots.isEmpty())
                ? Collections.unmodifiableList(new ArrayList<>(historySnapshots))
                : Collections.emptyList();
    }
}
