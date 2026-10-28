package io.github.atengk.boot.sample.mybatisplus.slowsql;

import io.github.atengk.boot.mybatisplus.slowsql.SlowSqlAlertProvider;
import io.github.atengk.boot.mybatisplus.slowsql.SlowSqlEvent;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * 示例慢 SQL 告警通知收集组件
 * 实现 SlowSqlAlertProvider SPI 接口，在内存中线程安全收集慢 SQL 告警事件用于端到端自验与链路监控断言
 *
 * @author Ateng
 * @since 2026-10-28
 */
@Component
public class SampleSlowSqlAlertProvider implements SlowSqlAlertProvider {

    private final List<SlowSqlEvent> events = new CopyOnWriteArrayList<>();

    @Override
    public void onSlowSql(SlowSqlEvent event) {
        if (event != null) {
            events.add(event);
        }
    }

    /**
     * 获取所有收集到的慢 SQL 告警事件（不可变快照）
     *
     * @return 慢 SQL 事件列表
     */
    public List<SlowSqlEvent> getEvents() {
        return Collections.unmodifiableList(new ArrayList<>(events));
    }

    /**
     * 获取最新触发的慢 SQL 告警事件
     *
     * @return 最新事件，若无事件则返回 null
     */
    public SlowSqlEvent getLatestEvent() {
        if (events.isEmpty()) {
            return null;
        }
        return events.get(events.size() - 1);
    }

    /**
     * 获取收集到的慢 SQL 事件数量
     *
     * @return 事件数量
     */
    public int size() {
        return events.size();
    }

    /**
     * 清空已收集的慢 SQL 告警事件
     */
    public void clear() {
        events.clear();
    }
}
