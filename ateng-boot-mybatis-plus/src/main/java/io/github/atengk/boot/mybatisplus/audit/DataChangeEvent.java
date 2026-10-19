package io.github.atengk.boot.mybatisplus.audit;

import org.springframework.context.ApplicationEvent;

import java.util.Collections;
import java.util.List;

/**
 * 数据变更审计 Spring 事件模型
 * 当发生数据变更且提交成功后发布，供下游声明式的 {@code @EventListener} 无缝解耦消费
 *
 * @author Ateng
 * @since 2026-10-19
 */
public class DataChangeEvent extends ApplicationEvent {

    private final List<DataChangeRecord> records;

    public DataChangeEvent(Object source, List<DataChangeRecord> records) {
        super(source);
        this.records = records != null ? List.copyOf(records) : Collections.emptyList();
    }

    /**
     * 获取数据变更审计记录列表
     *
     * @return 变更审计记录不可变列表
     */
    public List<DataChangeRecord> getRecords() {
        return records;
    }
}
