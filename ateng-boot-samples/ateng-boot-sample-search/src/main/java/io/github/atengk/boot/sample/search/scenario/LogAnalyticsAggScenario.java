package io.github.atengk.boot.sample.search.scenario;

import io.github.atengk.boot.sample.search.model.AccessLogDoc;
import io.github.atengk.boot.search.enums.SearchRefreshPolicy;
import io.github.atengk.boot.search.model.SearchPage;
import io.github.atengk.boot.search.query.SearchQuery;
import io.github.atengk.boot.search.query.SearchQueryBuilder;
import io.github.atengk.boot.search.template.SearchTemplate;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.Map;

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

    /**
     * 按路径筛选并批量订正客户端 IP 标记（参数化 Painless UpdateByQuery + WAIT_UNTIL 写后可见）
     *
     * @param path        接口路径过滤条件
     * @param newClientIp 新客户端 IP 值
     * @return 更新的日志文档数量
     */
    public long markLogsClientIp(String path, String newClientIp) {
        SearchQuery query = SearchQueryBuilder.create()
                .term("path", path)
                .build();
        return searchTemplate.updateByQuery(
                query,
                Map.of("clientIp", newClientIp),
                AccessLogDoc.class,
                SearchRefreshPolicy.WAIT_UNTIL);
    }

    /**
     * 统计日志大盘多维指标（全局去重 IP 数 Cardinality + 按日直方图及其每日独立 IP 子聚合）
     *
     * @param index 目标索引模式（如 "access_log_*"）
     * @return 携带嵌套强类型聚合结果的分页模型
     */
    public SearchPage<AccessLogDoc> analyzeAccessLogDashboard(String index) {
        SearchQuery query = SearchQueryBuilder.create()
                .cardinalityAggregation("unique_ips", "clientIp")
                .dateHistogramAggregation("daily_traffic", "timestamp", "1d",
                        sub -> sub.cardinalityAggregation("daily_unique_ips", "clientIp"))
                .size(0)
                .build();
        return searchTemplate.search(index, query, AccessLogDoc.class);
    }
}
