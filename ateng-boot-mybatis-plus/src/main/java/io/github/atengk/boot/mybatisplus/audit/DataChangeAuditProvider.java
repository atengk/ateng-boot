package io.github.atengk.boot.mybatisplus.audit;

import java.util.List;

/**
 * 数据变更审计消费 SPI 契约接口
 * 下游可通过注册此接口的 Spring Bean 自定义将变更记录持久化至数据库、Elasticsearch、消息队列等
 *
 * @author Ateng
 * @since 2026-10-19
 */
@FunctionalInterface
public interface DataChangeAuditProvider {

    /**
     * 数据变更记录消费回调
     *
     * @param records 数据变更审计记录列表
     */
    void onDataChanged(List<DataChangeRecord> records);
}
