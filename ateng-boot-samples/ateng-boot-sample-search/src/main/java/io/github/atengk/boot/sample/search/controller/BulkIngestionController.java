package io.github.atengk.boot.sample.search.controller;

import io.github.atengk.boot.core.model.Result;
import io.github.atengk.boot.sample.search.dto.BulkIngestRequest;
import io.github.atengk.boot.sample.search.dto.BulkSummaryResponse;
import io.github.atengk.boot.sample.search.dto.ProductSaveRequest;
import io.github.atengk.boot.sample.search.dto.SearchSampleConvertHelper;
import io.github.atengk.boot.sample.search.model.ProductDoc;
import io.github.atengk.boot.sample.search.scenario.BulkIngestionScenario;
import io.github.atengk.boot.search.model.SearchBulkFailure;
import io.github.atengk.boot.search.model.SearchBulkResult;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Collections;
import java.util.List;
import java.util.stream.Collectors;

/**
 * 海量流水数据批量写入与异步流式缓冲控制器
 *
 * @author Ateng
 * @since 2026-11-11
 */
@Slf4j
@Validated
@RestController
@RequestMapping("/sample/search/bulk")
@RequiredArgsConstructor
public class BulkIngestionController {

    private final BulkIngestionScenario bulkScenario;

    /**
     * 严格模式批量写入（遇任何单条写入失败立即抛出异常，Fail-Fast 保证全量事务性）
     */
    @PostMapping("/strict")
    public Result<BulkSummaryResponse> ingestStrict(@RequestBody @Valid BulkIngestRequest request) {
        List<ProductDoc> docs = request.getItems().stream()
                .map(SearchSampleConvertHelper::toDoc)
                .collect(Collectors.toList());
        SearchBulkResult bulkResult = bulkScenario.ingestBatchStrict(docs);
        return Result.success(toSummary(bulkResult, docs.size()));
    }

    /**
     * 宽容模式批量写入（自动收集局部失败项明细快照，不中断批次，专供死信隔离与重试）
     */
    @PostMapping("/lenient")
    public Result<BulkSummaryResponse> ingestLenient(@RequestBody @Valid BulkIngestRequest request) {
        List<ProductDoc> docs = request.getItems().stream()
                .map(SearchSampleConvertHelper::toDoc)
                .collect(Collectors.toList());
        SearchBulkResult bulkResult = bulkScenario.ingestBatchLenient(docs);
        return Result.success(toSummary(bulkResult, docs.size()));
    }

    /**
     * 异步流式批量缓冲写入（单条非阻塞投递至 SearchBulkProcessor）
     */
    @PostMapping("/stream-add")
    public Result<Boolean> streamIngest(@RequestBody @Valid ProductSaveRequest request) {
        ProductDoc doc = SearchSampleConvertHelper.toDoc(request);
        bulkScenario.streamIngest(doc);
        return Result.success(true);
    }

    /**
     * 显式手动排空当前流式批量缓冲区
     */
    @PostMapping("/stream-flush")
    public Result<Boolean> flushStream() {
        bulkScenario.flushStream();
        return Result.success(true);
    }

    private BulkSummaryResponse toSummary(SearchBulkResult bulkResult, int submittedTotal) {
        if (bulkResult == null) {
            return BulkSummaryResponse.builder()
                    .total((long) submittedTotal)
                    .successfulCount(0L)
                    .failedCount(0L)
                    .hasFailures(false)
                    .failureMessages(Collections.emptyList())
                    .build();
        }
        List<String> messages = bulkResult.getFailures().stream()
                .map(f -> String.format("[%s] id=%s, status=%s, reason=%s",
                        f.getIndex(), f.getId(), f.getStatus(), f.getErrorReason()))
                .collect(Collectors.toList());

        return BulkSummaryResponse.builder()
                .total((long) submittedTotal)
                .successfulCount((long) bulkResult.getSuccessCount())
                .failedCount((long) bulkResult.getFailureCount())
                .hasFailures(bulkResult.hasFailures())
                .failureMessages(messages)
                .build();
    }
}
