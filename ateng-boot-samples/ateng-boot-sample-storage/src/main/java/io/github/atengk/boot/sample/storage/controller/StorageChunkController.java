package io.github.atengk.boot.sample.storage.controller;

import io.github.atengk.boot.core.model.Result;
import io.github.atengk.boot.storage.cleaner.StorageChunkCleaner;
import io.github.atengk.boot.storage.model.PresignedUrl;
import io.github.atengk.boot.storage.model.StorageObject;
import io.github.atengk.boot.storage.template.StorageOperations;
import io.github.atengk.boot.storage.template.StorageTemplate;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.io.InputStream;
import java.time.Duration;

/**
 * 大文件分片上传状态机、端侧直传与孤儿碎片治理控制器
 * 演示服务端分片会话生命周期（初始化、逐片上传、正序合并与缺片防卫 40012）、端侧分块 HTTP PUT 直传预签名外链、
 * 主动中止清理与后台孤儿碎片守护扫描
 *
 * @author Ateng
 * @since 2026-11-08
 */
@Slf4j
@Validated
@RestController
@RequestMapping("/sample/storage/chunk")
public class StorageChunkController {

    private final StorageTemplate storageTemplate;
    private final StorageChunkCleaner chunkCleaner;

    public StorageChunkController(
            StorageTemplate storageTemplate,
            @Autowired(required = false) StorageChunkCleaner chunkCleaner) {
        this.storageTemplate = storageTemplate;
        this.chunkCleaner = chunkCleaner;
    }

    /**
     * 初始化大文件分片上传会话，获取唯一 uploadId
     *
     * @param path   目标存储相对路径
     * @param source 目标存储源标识（可选，默认当前活动源）
     * @return 分片上传会话标识 uploadId
     */
    @PostMapping("/initiate")
    public Result<String> initiateChunk(
            @RequestParam("path") @NotBlank(message = "目标存储路径不能为空") String path,
            @RequestParam(value = "source", required = false) String source) {
        StorageOperations ops = getOperations(source);
        String uploadId = ops.initiateChunkedUpload(path);
        return Result.success(uploadId);
    }

    /**
     * 上传单个分片数据（1-based 序号连续性约束）
     *
     * @param uploadId   分片上传会话标识
     * @param chunkIndex 分片正序编号（从 1 开始）
     * @param file       分片文件表单数据
     * @param source     目标存储源标识（可选，默认当前活动源）
     * @return 统一成功响应
     * @throws IOException IO 读取异常
     */
    @PostMapping("/upload")
    public Result<Void> uploadChunk(
            @RequestParam("uploadId") @NotBlank(message = "会话标识 uploadId 不能为空") String uploadId,
            @RequestParam("chunkIndex") @Min(value = 1, message = "分片序号必须从 1 开始") int chunkIndex,
            @RequestParam("file") @NotNull(message = "分片文件不能为空") MultipartFile file,
            @RequestParam(value = "source", required = false) String source) throws IOException {
        StorageOperations ops = getOperations(source);
        try (InputStream in = file.getInputStream()) {
            ops.uploadChunk(uploadId, chunkIndex, in, file.getSize());
        }
        return Result.success();
    }

    /**
     * 获取端侧大文件分片 HTTP PUT 直传预签名外链凭据
     *
     * @param path            目标存储相对路径
     * @param uploadId        分片上传会话标识
     * @param chunkIndex      分片序号（1-based）
     * @param durationSeconds 外链有效时长（秒，默认 300 秒）
     * @param source          目标存储源标识（可选，默认当前活动源）
     * @return 分片预签名直传凭据
     */
    @GetMapping("/presigned-part-url")
    public Result<PresignedUrl> getPresignedUploadPartUrl(
            @RequestParam("path") @NotBlank(message = "目标路径不能为空") String path,
            @RequestParam("uploadId") @NotBlank(message = "uploadId 不能为空") String uploadId,
            @RequestParam("chunkIndex") @Min(value = 1, message = "分片序号必须 >= 1") int chunkIndex,
            @RequestParam(value = "durationSeconds", defaultValue = "300") @Min(value = 1, message = "有效期必须大于 0") long durationSeconds,
            @RequestParam(value = "source", required = false) String source) {
        StorageOperations ops = getOperations(source);
        PresignedUrl presignedUrl = ops.getPresignedUploadPartUrl(path, uploadId, chunkIndex, Duration.ofSeconds(durationSeconds));
        return Result.success(presignedUrl);
    }

    /**
     * 触发分片合并落盘（严格校验 1 ~ totalChunks 连续性，缺片快速失败返回 40012）
     *
     * @param uploadId    分片上传会话标识
     * @param path        最终落盘对象相对路径
     * @param totalChunks 分片总数
     * @param source      目标存储源标识（可选，默认当前活动源）
     * @return 合并完成后的对象元数据
     */
    @PostMapping("/complete")
    public Result<StorageObject> completeChunk(
            @RequestParam("uploadId") @NotBlank(message = "会话标识 uploadId 不能为空") String uploadId,
            @RequestParam("path") @NotBlank(message = "目标存储路径不能为空") String path,
            @RequestParam("totalChunks") @Min(value = 1, message = "总片数必须 >= 1") int totalChunks,
            @RequestParam(value = "source", required = false) String source) {
        StorageOperations ops = getOperations(source);
        StorageObject object = ops.completeChunkedUpload(uploadId, path, totalChunks);
        return Result.success(object);
    }

    /**
     * 主动中止分片上传会话，递归物理清理临时碎片目录
     *
     * @param uploadId 分片上传会话标识
     * @param source   目标存储源标识（可选，默认当前活动源）
     * @return 统一成功响应
     */
    @PostMapping("/abort")
    public Result<Void> abortChunk(
            @RequestParam("uploadId") @NotBlank(message = "会话标识 uploadId 不能为空") String uploadId,
            @RequestParam(value = "source", required = false) String source) {
        StorageOperations ops = getOperations(source);
        ops.abortChunkedUpload(uploadId);
        return Result.success();
    }

    /**
     * 手动触发扫描并清理所有存储源的超期孤儿分片碎片会话
     *
     * @return 本次清理释放的孤儿分片会话总数
     */
    @PostMapping("/clean-expired")
    public Result<Integer> cleanExpired() {
        int cleanedCount = (chunkCleaner != null) ? chunkCleaner.triggerClean() : 0;
        return Result.success(cleanedCount);
    }

    private StorageOperations getOperations(String source) {
        return (source != null && !source.isBlank()) ? storageTemplate.use(source) : storageTemplate;
    }
}
