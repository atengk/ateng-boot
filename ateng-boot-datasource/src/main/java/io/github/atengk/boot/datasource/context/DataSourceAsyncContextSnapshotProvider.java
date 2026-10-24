package io.github.atengk.boot.datasource.context;

import com.baomidou.dynamic.datasource.toolkit.DynamicDataSourceContextHolder;
import io.github.atengk.boot.core.context.AsyncContextSnapshotProvider;

/**
 * 数据源上下文异步全链路快照提供者
 * 将当前线程的 {@link DynamicDataSourceContextHolder} 数据源 Key 快照捕获并无缝透传至异步工作线程池，
 * 升级形成五维上下文体系：$\langle\text{MDC TraceId}, \text{UserId}, \text{TenantId}, \text{IgnoreTenant}, \mathbf{DataSourceKey}\rangle$，
 * 并在任务执行完成后于 finally 中彻底清理还原现场，严密防御线程池污染与跨库串号
 *
 * @author Ateng
 * @since 2026-10-24
 */
public class DataSourceAsyncContextSnapshotProvider implements AsyncContextSnapshotProvider {

    @Override
    public Object capture() {
        return DynamicDataSourceContextHolder.peek();
    }

    @Override
    public Object capturePrevious() {
        return DynamicDataSourceContextHolder.peek();
    }

    @Override
    public void apply(Object snapshot) {
        if (snapshot instanceof String dsKey && !dsKey.isBlank()) {
            DynamicDataSourceContextHolder.push(dsKey);
        } else {
            // 若父线程无数据源上下文，确保工作线程处于纯净无切源状态
            DynamicDataSourceContextHolder.poll();
        }
    }

    @Override
    public void restore(Object previous) {
        // 彻底弹出工作任务期间压入的上下文
        DynamicDataSourceContextHolder.poll();
        if (previous instanceof String dsKey && !dsKey.isBlank()) {
            DynamicDataSourceContextHolder.push(dsKey);
        }
    }
}
