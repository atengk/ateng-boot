package io.github.atengk.boot.sample.mybatisplus.sqllog;

import io.github.atengk.boot.core.audit.SqlLogModel;
import io.github.atengk.boot.mybatisplus.sqllog.SqlLogStorageProvider;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * 内存物理 SQL 结构化审计日志收集提供者
 * 实现 SqlLogStorageProvider SPI 接口，在内存中线程安全收集物理执行 SQL 审计模型
 * 用于端到端集成测试断言与可观测诊断看板查询
 *
 * @author Ateng
 * @since 2026-11-08
 */
@Component
public class MemorySqlLogStorageProvider implements SqlLogStorageProvider {

    private final List<SqlLogModel> logs = new CopyOnWriteArrayList<>();

    @Override
    public void store(SqlLogModel logModel) {
        if (logModel != null) {
            logs.add(logModel);
        }
    }

    /**
     * 获取所有收集到的 SQL 审计记录列表（不可变快照）
     *
     * @return SQL 审计模型列表
     */
    public List<SqlLogModel> getLogs() {
        return Collections.unmodifiableList(new ArrayList<>(logs));
    }

    /**
     * 获取最新一条 SQL 审计记录
     *
     * @return 最新 SQL 审计模型，若无记录返回 null
     */
    public SqlLogModel getLatestLog() {
        if (logs.isEmpty()) {
            return null;
        }
        return logs.get(logs.size() - 1);
    }

    /**
     * 获取收集到的 SQL 审计记录数量
     *
     * @return 记录数量
     */
    public int size() {
        return logs.size();
    }

    /**
     * 清空已收集的 SQL 审计记录
     */
    public void clear() {
        logs.clear();
    }
}
