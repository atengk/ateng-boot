package io.github.atengk.boot.sample.search.scenario;

import io.github.atengk.boot.sample.search.model.AccessLogDoc;
import io.github.atengk.boot.search.model.SearchPage;
import io.github.atengk.boot.search.query.SearchQuery;
import io.github.atengk.boot.search.query.SearchQueryBuilder;
import io.github.atengk.boot.search.template.SearchTemplate;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;

/**
 * 日志审计与时序分析大盘业务场景组件
 * <p>
 * 涵盖 SpEL 动态时序按天分表写入、DateHistogram 直方图时间分桶聚合走势统计、count 计数与 deleteByQuery 历史冷数据批量清理。
 *
 * @author Ateng
 * @since 2026-11-02
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class LogAnalyticsAggScenario {

    private final SearchTemplate searchTemplate;

    /**
     * 写入审计访问日志（基于 SpEL 表达式动态路由至当天索引，如 access_log_20261102）
     *
     * @param logDoc 日志实体
     */
    public void recordAccessLog(AccessLogDoc logDoc) {
        searchTemplate.save(logDoc);
    }

    /**
     * 按时序日历间隔（如 "1d", "1h"）分桶统计流量走势
     *
     * @param index            目标索引模式（如 "access_log_*"）
     * @param calendarInterval 日历分桶间隔（如 "1d", "1h"）
     * @return 携带聚合桶的分页查询模型
     */
    public SearchPage<AccessLogDoc> aggregateTrafficTrend(String index, String calendarInterval) {
        SearchQuery query = SearchQueryBuilder.create()
                .dateHistogramAggregation("daily_traffic", "timestamp", calendarInterval)
                .size(0)
                .build();
        return searchTemplate.search(index, query, AccessLogDoc.class);
    }

    /**
     * 统计指定 API 路径下的访问日志总量
     *
     * @param path 请求路径
     * @return 匹配文档数
     */
    public long countLogsByPath(String path) {
        SearchQuery query = SearchQueryBuilder.create()
                .term("path", path)
                .build();
        return searchTemplate.count(query, AccessLogDoc.class);
    }

    /**
     * 条件驱动批量物理清理指定截止时间之前的过期历史日志 (deleteByQuery)
     *
     * @param cutoffTime 截止时间
     * @return 物理删除的文档总数
     */
    public long purgeHistoricalLogsBefore(LocalDateTime cutoffTime) {
        SearchQuery query = SearchQueryBuilder.create()
                .lt("timestamp", cutoffTime)
                .build();
        return searchTemplate.deleteByQuery(query, AccessLogDoc.class);
    }
}
