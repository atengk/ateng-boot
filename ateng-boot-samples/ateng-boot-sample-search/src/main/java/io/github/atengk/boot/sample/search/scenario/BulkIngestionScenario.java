package io.github.atengk.boot.sample.search.scenario;

import io.github.atengk.boot.sample.search.model.ProductDoc;
import io.github.atengk.boot.search.bulk.SearchBulkProcessor;
import io.github.atengk.boot.search.model.SearchBulkResult;
import io.github.atengk.boot.search.template.SearchTemplate;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * 海量流水数据批量写入与异步流式缓冲业务场景组件
 * <p>
 * 涵盖严格批量全事务性写入、宽容局部失败批量隔离以及基于受管 SearchBulkProcessor 的高吞吐非阻塞缓冲入库。
 *
 * @author Ateng
 * @since 2026-11-02
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class BulkIngestionScenario {

    private final SearchTemplate searchTemplate;
    private final SearchBulkProcessor searchBulkProcessor;

    /**
     * 严格批量写入（遇任何单条解析/网络失败即抛出异常，保证全量事务性）
     *
     * @param products 待写入文档列表
     * @return 批量写入结果
     */
    public SearchBulkResult ingestBatchStrict(List<ProductDoc> products) {
        return searchTemplate.indexBatch(products);
    }

    /**
     * 宽容批量写入（自动吞吐容错，收集单条失败详情并返回，不中断批次）
     *
     * @param products 待写入文档列表
     * @return 包含成功与失败统计的批量结果模型
     */
    public SearchBulkResult ingestBatchLenient(List<ProductDoc> products) {
        return searchTemplate.indexBatchLenient(products);
    }

    /**
     * 异步流式批量缓冲写入（单条非阻塞投递）
     *
     * @param product 单条商品文档
     */
    public void streamIngest(ProductDoc product) {
        searchBulkProcessor.add(product);
    }

    /**
     * 异步流式批量缓冲写入（集合投递并即时触发排空）
     *
     * @param products 商品文档列表
     */
    public void streamIngestBatchAndFlush(List<ProductDoc> products) {
        searchBulkProcessor.addAll(products);
        searchBulkProcessor.flush();
    }

    /**
     * 显式强制排空当前流式批量缓冲区
     */
    public void flushStream() {
        searchBulkProcessor.flush();
    }
}
