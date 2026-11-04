package io.github.atengk.boot.sample.milvus.controller;

import io.github.atengk.boot.core.model.Result;
import io.github.atengk.boot.milvus.template.MilvusTemplate;
import io.github.atengk.boot.sample.milvus.model.SampleDocumentEntity;
import io.github.atengk.boot.sample.milvus.vo.MilvusCollectionVo;
import io.github.atengk.boot.sample.milvus.vo.MilvusServerStatusVo;
import io.milvus.v2.service.collection.response.DescribeCollectionResp;
import jakarta.validation.constraints.NotBlank;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * Milvus 集合生命周期与健康运维控制器
 *
 * @author Ateng
 * @since 2026-11-04
 */
@Slf4j
@Validated
@RestController
@RequestMapping("/sample/milvus/lifecycle")
@RequiredArgsConstructor
public class MilvusLifecycleController {

    private final MilvusTemplate milvusTemplate;

    /**
     * 探测 Milvus 服务端连通性与版本信息
     *
     * @return 统一响应契约包装的服务端状态 VO
     */
    @GetMapping("/health")
    public Result<MilvusServerStatusVo> checkHealth() {
        boolean ready = milvusTemplate.testConnection();
        String version = ready ? milvusTemplate.getServerVersion() : "UNKNOWN";
        return Result.success(new MilvusServerStatusVo(ready, version));
    }

    /**
     * 一键初始化样例集合、向量索引并预加载至内存
     *
     * @return 集合初始化结果
     */
    @PostMapping("/collection/init")
    public Result<String> initCollection() {
        milvusTemplate.createCollectionAndIndex(SampleDocumentEntity.class);
        return Result.success("集合与向量索引初始化成功并已就绪加载");
    }

    /**
     * 列出所有集合名称列表
     *
     * @return 集合名称列表
     */
    @GetMapping("/collection/list")
    public Result<List<String>> listCollections() {
        return Result.success(milvusTemplate.listCollections());
    }

    /**
     * 查看指定集合的元数据与状态详情
     *
     * @param collectionName 集合名称
     * @return 集合结构详情 VO
     */
    @GetMapping("/collection/describe")
    public Result<MilvusCollectionVo> describeCollection(
            @RequestParam(name = "collectionName", defaultValue = "sample_document")
            @NotBlank(message = "集合名称不能为空") String collectionName) {
        DescribeCollectionResp resp = milvusTemplate.describeCollection(collectionName);
        boolean loaded = milvusTemplate.isCollectionLoaded(collectionName);
        long count = 0;
        try {
            count = milvusTemplate.count(collectionName);
        } catch (Exception ignored) {
        }
        return Result.success(MilvusCollectionVo.builder()
                .collectionName(resp.getCollectionName())
                .description(resp.getDescription())
                .loadState(loaded ? "Loaded" : "NotLoaded")
                .rowCount(count)
                .build());
    }

    /**
     * 显式加载集合至内存以备检索
     *
     * @param collectionName 集合名称
     * @return 操作成功标识
     */
    @PostMapping("/collection/load")
    public Result<Boolean> loadCollection(
            @RequestParam(name = "collectionName", defaultValue = "sample_document")
            @NotBlank(message = "集合名称不能为空") String collectionName) {
        milvusTemplate.loadCollection(collectionName);
        return Result.success(true);
    }

    /**
     * 显式从内存释放集合
     *
     * @param collectionName 集合名称
     * @return 操作成功标识
     */
    @PostMapping("/collection/release")
    public Result<Boolean> releaseCollection(
            @RequestParam(name = "collectionName", defaultValue = "sample_document")
            @NotBlank(message = "集合名称不能为空") String collectionName) {
        milvusTemplate.releaseCollection(collectionName);
        return Result.success(true);
    }

    /**
     * 删除指定集合
     *
     * @param collectionName 集合名称
     * @return 操作成功标识
     */
    @DeleteMapping("/collection")
    public Result<Boolean> dropCollection(
            @RequestParam(name = "collectionName", defaultValue = "sample_document")
            @NotBlank(message = "集合名称不能为空") String collectionName) {
        milvusTemplate.dropCollection(collectionName);
        return Result.success(true);
    }
}
