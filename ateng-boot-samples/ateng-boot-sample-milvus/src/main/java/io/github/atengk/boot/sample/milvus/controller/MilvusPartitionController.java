package io.github.atengk.boot.sample.milvus.controller;

import io.github.atengk.boot.core.model.Result;
import io.github.atengk.boot.milvus.template.MilvusTemplate;
import io.github.atengk.boot.sample.milvus.dto.PartitionArchiveInsertDto;
import io.github.atengk.boot.sample.milvus.dto.PartitionBatchOpDto;
import io.github.atengk.boot.sample.milvus.dto.PartitionOpDto;
import io.github.atengk.boot.sample.milvus.model.SampleArchiveLogEntity;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.util.StringUtils;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.Collections;
import java.util.List;

/**
 * Milvus 物理分区生命周期运维与时序自动建分区归档控制器
 *
 * @author Ateng
 * @since 2026-11-20
 */
@Slf4j
@Validated
@RestController
@RequestMapping("/sample/milvus/partition")
@RequiredArgsConstructor
public class MilvusPartitionController {

    private static final String DEFAULT_COLLECTION = "sample_archive_log";

    private final MilvusTemplate milvusTemplate;

    /**
     * 初始化按月归档日志集合与索引
     *
     * @return 初始化结果描述
     */
    @PostMapping("/init")
    public Result<String> initArchiveCollection() {
        milvusTemplate.createCollectionAndIndex(SampleArchiveLogEntity.class);
        return Result.success("归档日志集合与向量索引初始化成功并已就绪加载");
    }

    /**
     * 创建物理分区
     *
     * @param dto 分区运维请求 DTO
     * @return 操作成功标识
     */
    @PostMapping("/create")
    public Result<Boolean> createPartition(@Valid @RequestBody PartitionOpDto dto) {
        String collection = resolveCollectionName(dto.getCollectionName());
        milvusTemplate.createPartition(collection, dto.getPartitionName().trim());
        return Result.success(true);
    }

    /**
     * 列出集合内全部物理分区名称
     *
     * @param collectionName 集合名称
     * @return 物理分区名称列表
     */
    @GetMapping("/list")
    public Result<List<String>> listPartitions(
            @RequestParam(name = "collectionName", defaultValue = DEFAULT_COLLECTION)
            @NotBlank(message = "集合名称不能为空") String collectionName) {
        return Result.success(milvusTemplate.listPartitions(collectionName.trim()));
    }

    /**
     * 加载指定物理分区列表至内存
     *
     * @param dto 批量分区请求 DTO
     * @return 操作成功标识
     */
    @PostMapping("/load")
    public Result<Boolean> loadPartitions(@Valid @RequestBody PartitionBatchOpDto dto) {
        String collection = resolveCollectionName(dto.getCollectionName());
        milvusTemplate.loadPartitions(collection, dto.getPartitionNames());
        return Result.success(true);
    }

    /**
     * 探测指定物理分区列表是否已全部加载至内存
     *
     * @param collectionName 集合名称
     * @param partitionNames 物理分区名称列表
     * @return 全部分区均已加载时返回 true，否则返回 false
     */
    @GetMapping("/loaded")
    public Result<Boolean> isPartitionsLoaded(
            @RequestParam(name = "collectionName", defaultValue = DEFAULT_COLLECTION)
            @NotBlank(message = "集合名称不能为空") String collectionName,
            @RequestParam(name = "partitionNames")
            @NotEmpty(message = "分区名称列表不能为空") List<@NotBlank(message = "分区名称不能为空") String> partitionNames) {
        return Result.success(milvusTemplate.isPartitionsLoaded(collectionName.trim(), partitionNames));
    }

    /**
     * 从内存释放指定物理分区列表
     *
     * @param dto 批量分区请求 DTO
     * @return 操作成功标识
     */
    @PostMapping("/release")
    public Result<Boolean> releasePartitions(@Valid @RequestBody PartitionBatchOpDto dto) {
        String collection = resolveCollectionName(dto.getCollectionName());
        milvusTemplate.releasePartitions(collection, dto.getPartitionNames());
        return Result.success(true);
    }

    /**
     * 删除指定物理分区
     *
     * @param collectionName 集合名称
     * @param partitionName  物理分区名称
     * @return 操作成功标识
     */
    @DeleteMapping
    public Result<Boolean> dropPartition(
            @RequestParam(name = "collectionName", defaultValue = DEFAULT_COLLECTION)
            @NotBlank(message = "集合名称不能为空") String collectionName,
            @RequestParam(name = "partitionName")
            @NotBlank(message = "分区名称不能为空") String partitionName) {
        milvusTemplate.dropPartition(collectionName.trim(), partitionName.trim());
        return Result.success(true);
    }

    /**
     * 时序归档写入 (开启 auto-create-partition=true 时自动补建目标物理分区)
     *
     * @param dto 归档写入请求 DTO
     * @return 写入成功的主键 ID
     */
    @PostMapping("/archive-insert")
    public Result<Long> archiveInsert(@Valid @RequestBody PartitionArchiveInsertDto dto) {
        SampleArchiveLogEntity entity = SampleArchiveLogEntity.builder()
                .id(dto.getId())
                .archiveMonth(dto.getArchiveMonth().trim())
                .title(dto.getTitle().trim())
                .vector(dto.getVector())
                .build();
        milvusTemplate.insertEntities(Collections.singletonList(entity), dto.getPartitionName().trim());
        return Result.success(entity.getId());
    }

    private String resolveCollectionName(String collectionName) {
        return StringUtils.hasText(collectionName) ? collectionName.trim() : DEFAULT_COLLECTION;
    }
}
