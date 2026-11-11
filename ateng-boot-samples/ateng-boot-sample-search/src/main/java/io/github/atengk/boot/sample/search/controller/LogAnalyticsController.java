package io.github.atengk.boot.sample.search.controller;

import io.github.atengk.boot.core.model.Result;
import io.github.atengk.boot.sample.search.dto.AccessLogPurgeRequest;
import io.github.atengk.boot.sample.search.dto.AccessLogRecordRequest;
import io.github.atengk.boot.sample.search.dto.SearchSampleConvertHelper;
import io.github.atengk.boot.sample.search.dto.TrafficTrendResponse;
import io.github.atengk.boot.sample.search.model.AccessLogDoc;
import io.github.atengk.boot.sample.search.scenario.LogAnalyticsAggScenario;
import io.github.atengk.boot.search.model.SearchPage;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 日志审计与时序聚合分析控制器
 *
 * @author Ateng
 * @since 2026-11-11
 */
@Slf4j
@Validated
@RestController
@RequestMapping("/sample/search/log")
@RequiredArgsConstructor
public class LogAnalyticsController {

    private final LogAnalyticsAggScenario logScenario;

    /**
     * 写入审计访问日志（基于 SpEL 表达式动态路由至按天分表索引）
     */
    @PostMapping("/record")
    public Result<String> recordAccessLog(@RequestBody @Valid AccessLogRecordRequest request) {
        AccessLogDoc doc = SearchSampleConvertHelper.toDoc(request);
        logScenario.recordAccessLog(doc);
        return Result.success(doc.getId());
    }

    /**
     * 统计指定 API 路径下的访问日志总量 (轻量 Count 端点)
     */
    @GetMapping("/count")
    public Result<Long> countLogsByPath(@RequestParam String path) {
        long count = logScenario.countLogsByPath(path);
        return Result.success(count);
    }

    /**
     * 按时序日历间隔（如 "1d", "1h"）分桶统计流量走势 (DateHistogram 直方图聚合)
     */
    @GetMapping("/trend")
    public Result<List<TrafficTrendResponse>> aggregateTrafficTrend(
            @RequestParam(defaultValue = "access_log_*") String index,
            @RequestParam(defaultValue = "1d") String interval) {
        SearchPage<AccessLogDoc> page = logScenario.aggregateTrafficTrend(index, interval);
        return Result.success(SearchSampleConvertHelper.toTrafficTrends(page.getAggregations()));
    }

    /**
     * 条件驱动批量物理清理指定截止时间之前的过期历史日志 (deleteByQuery，支持多租户安全网)
     */
    @PostMapping("/purge")
    public Result<Long> purgeHistoricalLogs(@RequestBody @Valid AccessLogPurgeRequest request) {
        long deletedCount = logScenario.purgeHistoricalLogsBefore(request.getCutoffTime());
        return Result.success(deletedCount);
    }
}
