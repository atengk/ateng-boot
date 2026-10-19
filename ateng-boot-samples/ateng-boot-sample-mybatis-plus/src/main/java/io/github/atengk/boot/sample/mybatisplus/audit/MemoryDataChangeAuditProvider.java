package io.github.atengk.boot.sample.mybatisplus.audit;

import io.github.atengk.boot.mybatisplus.audit.DataChangeAuditProvider;
import io.github.atengk.boot.mybatisplus.audit.DataChangeRecord;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * 内存数据变更 Diff 审计收集提供者
 * 实现 DataChangeAuditProvider SPI，内存安全收集数据变更记录用于端到端自验
 *
 * @author Ateng
 * @since 2026-10-19
 */
@Component
public class MemoryDataChangeAuditProvider implements DataChangeAuditProvider {

    private final List<DataChangeRecord> records = new CopyOnWriteArrayList<>();

    @Override
    public void onDataChanged(List<DataChangeRecord> dataChangeRecords) {
        if (dataChangeRecords != null) {
            records.addAll(dataChangeRecords);
        }
    }

    public List<DataChangeRecord> getRecords() {
        return records;
    }

    public void clear() {
        records.clear();
    }
}
