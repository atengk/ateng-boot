package io.github.atengk.boot.sample.storage.controller;

import io.github.atengk.boot.core.model.PageQuery;
import io.github.atengk.boot.core.model.PageResult;
import io.github.atengk.boot.core.model.Result;
import io.github.atengk.boot.sample.storage.service.FileStorageDemoService;
import io.github.atengk.boot.storage.model.PresignedUrl;
import io.github.atengk.boot.storage.model.StorageObject;
import io.github.atengk.boot.storage.resource.StorageResource;
import io.github.atengk.boot.storage.template.StorageTemplate;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.io.Resource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Map;

/**
 * 样例工程通用存储 Web 控制器
 * 演示文件上传、流式下载（含 HTTP Range 206 断点续传）、HMAC 预签名外链、动态多源切源、
 * 对象复移、目录列举与分页、便捷存取、流式 ZIP 打包与大文件分片合并全生命周期
 *
 * @author Ateng
 * @since 2026-10-17
 */
@Slf4j
@RestController
@RequestMapping("/api/storage")
@RequiredArgsConstructor
public class FileController {

    private final StorageTemplate storageTemplate;
    private final FileStorageDemoService fileStorageDemoService;

    /**
     * 上传文件到默认存储源
     *
     * @param file 上传的表单文件
     * @param path 可选自定义存储路径（为空则使用原始文件名）
     * @return 存储元数据强类型契约
     */
    @PostMapping("/upload")
    public Result<StorageObject> upload(
            @RequestParam("file") MultipartFile file,
            @RequestParam(value = "path", required = false) String path) {
        String targetPath = (path != null && !path.isBlank()) ? path : file.getOriginalFilename();
        StorageObject object = storageTemplate.upload(targetPath, file);
        return Result.success(object);
    }

    /**
     * 流式下载存储文件（支持 Spring 原生 HTTP Range 206 断点续传）
     *
     * @param path 目标相对路径
     * @return 资源文件响应体
     * @throws IOException IO 异常
     */
    @GetMapping("/download")
    public ResponseEntity<Resource> download(@RequestParam("path") String path) throws IOException {
        StorageResource resource = storageTemplate.getResource(path);
        String filename = resource.getFilename();
        String encodedFilename = URLEncoder.encode(filename, StandardCharsets.UTF_8).replace("+", "%20");

        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"" + encodedFilename + "\"; filename*=UTF-8''" + encodedFilename)
                .contentType(MediaType.parseMediaType(resource.getContentType()))
                .contentLength(resource.contentLength())
                .body(resource);
    }

    /**
     * 获取指定存储对象的 HMAC 预签名只读下载外链
     *
     * @param path            目标相对路径
     * @param durationSeconds 外链有效秒数（默认 300 秒）
     * @return 预签名外链凭据
     */
    @GetMapping("/presigned-url")
    public Result<PresignedUrl> getPresignedUrl(
            @RequestParam("path") String path,
            @RequestParam(value = "durationSeconds", defaultValue = "300") long durationSeconds) {
        PresignedUrl presignedUrl = storageTemplate.getPresignedUrl(path, Duration.ofSeconds(durationSeconds));
        return Result.success(presignedUrl);
    }

    /**
     * 上传文件到 backup 隔离备份源（演示 @Storage 声明式切源）
     *
     * @param file 上传的表单文件
     * @param path 可选自定义存储路径
     * @return 存储元数据强类型契约
     * @throws IOException IO 异常
     */
    @PostMapping("/upload-backup")
    public Result<StorageObject> uploadToBackup(
            @RequestParam("file") MultipartFile file,
            @RequestParam(value = "path", required = false) String path) throws IOException {
        String targetPath = (path != null && !path.isBlank()) ? path : file.getOriginalFilename();
        StorageObject object = fileStorageDemoService.uploadToBackup(
                targetPath,
                file.getInputStream(),
                file.getContentType(),
                file.getSize()
        );
        return Result.success(object);
    }

    /**
     * 跨路径复制存储对象
     *
     * @param sourcePath 源路径
     * @param targetPath 目标路径
     * @return 操作结果
     */
    @PostMapping("/copy")
    public Result<Boolean> copy(
            @RequestParam("sourcePath") String sourcePath,
            @RequestParam("targetPath") String targetPath) {
        boolean success = storageTemplate.copy(sourcePath, targetPath);
        return Result.success(success);
    }

    /**
     * 跨路径移动存储对象
     *
     * @param sourcePath 源路径
     * @param targetPath 目标路径
     * @return 操作结果
     */
    @PostMapping("/move")
    public Result<Boolean> move(
            @RequestParam("sourcePath") String sourcePath,
            @RequestParam("targetPath") String targetPath) {
        boolean success = storageTemplate.move(sourcePath, targetPath);
        return Result.success(success);
    }

    /**
     * 删除目标存储对象（天然幂等）
     *
     * @param path 目标相对路径
     * @return 操作结果
     */
    @PostMapping("/delete")
    public Result<Boolean> delete(@RequestParam("path") String path) {
        boolean success = storageTemplate.delete(path);
        return Result.success(success);
    }

    /**
     * 列举指定前缀下的存储对象
     *
     * @param prefix    前缀路径（为空列举根目录）
     * @param recursive 是否递归子目录
     * @return 存储对象列表
     */
    @GetMapping("/list")
    public Result<List<StorageObject>> list(
            @RequestParam(value = "prefix", required = false) String prefix,
            @RequestParam(value = "recursive", defaultValue = "false") boolean recursive) {
        List<StorageObject> list = storageTemplate.list(prefix, recursive);
        return Result.success(list);
    }

    /**
     * 分页检索指定前缀下的存储对象
     *
     * @param prefix    前缀路径（为空检索根目录）
     * @param pageQuery 分页参数
     * @param recursive 是否递归子目录
     * @return 标准分页结果契约
     */
    @GetMapping("/list-page")
    public Result<PageResult<StorageObject>> listPage(
            @RequestParam(value = "prefix", required = false) String prefix,
            @ModelAttribute PageQuery pageQuery,
            @RequestParam(value = "recursive", defaultValue = "false") boolean recursive) {
        PageResult<StorageObject> pageResult = storageTemplate.listPage(prefix, pageQuery, recursive);
        return Result.success(pageResult);
    }

    /**
     * 便捷纯文本字符串上传
     *
     * @param path    目标路径
     * @param content 文本内容
     * @return 存储元数据契约
     */
    @PostMapping("/write-string")
    public Result<StorageObject> writeString(
            @RequestParam("path") String path,
            @RequestParam("content") String content) {
        StorageObject object = storageTemplate.uploadString(path, content);
        return Result.success(object);
    }

    /**
     * 便捷纯文本字符串下载读取
     *
     * @param path 目标路径
     * @return 纯文本内容
     */
    @GetMapping("/read-string")
    public Result<String> readString(@RequestParam("path") String path) {
        String content = storageTemplate.downloadString(path);
        return Result.success(content);
    }

    /**
     * 便捷 JSON 对象上传
     *
     * @param path 目标路径
     * @param data JSON 载荷对象
     * @return 存储元数据契约
     */
    @PostMapping("/write-json")
    public Result<StorageObject> writeJson(
            @RequestParam("path") String path,
            @RequestBody Object data) {
        StorageObject object = storageTemplate.uploadJson(path, data);
        return Result.success(object);
    }

    /**
     * 便捷 JSON 对象下载读取
     *
     * @param path 目标路径
     * @return Map 键值对载荷
     */
    @GetMapping("/read-json")
    public Result<Map<String, Object>> readJson(@RequestParam("path") String path) {
        @SuppressWarnings("unchecked")
        Map<String, Object> data = storageTemplate.downloadJson(path, Map.class);
        return Result.success(data);
    }

    /**
     * 多文件流式打包 ZIP 并通过 HTTP 响应输出下载（零磁盘临时文件）
     *
     * @param paths       文件路径列表
     * @param zipFilename 客户端下载压缩包文件名
     * @param response    HTTP 响应
     */
    @GetMapping("/download-zip")
    public void downloadZip(
            @RequestParam("paths") List<String> paths,
            @RequestParam(value = "zipFilename", defaultValue = "download.zip") String zipFilename,
            HttpServletResponse response) {
        storageTemplate.downloadAsZip(paths, response, zipFilename);
    }

    /**
     * 初始化大文件分片上传会话
     *
     * @param path 目标存储路径
     * @return 全局唯一分片上传会话标识 uploadId
     */
    @PostMapping("/chunk/initiate")
    public Result<String> initiateChunk(@RequestParam("path") String path) {
        String uploadId = storageTemplate.initiateChunkedUpload(path);
        return Result.success(uploadId);
    }

    /**
     * 上传单个分片数据（1-based 序号）
     *
     * @param uploadId   分片上传会话标识
     * @param chunkIndex 分片正序编号（从 1 开始）
     * @param file       分片文件表单
     * @return 统一成功契约
     * @throws IOException IO 异常
     */
    @PostMapping("/chunk/upload")
    public Result<Void> uploadChunk(
            @RequestParam("uploadId") String uploadId,
            @RequestParam("chunkIndex") int chunkIndex,
            @RequestParam("file") MultipartFile file) throws IOException {
        storageTemplate.uploadChunk(uploadId, chunkIndex, file.getInputStream(), file.getSize());
        return Result.success();
    }

    /**
     * 完成分片合并并正序落盘
     *
     * @param uploadId    分片上传会话标识
     * @param path        最终落盘对象路径
     * @param totalChunks 总片数
     * @return 合并完成后的对象元数据
     */
    @PostMapping("/chunk/complete")
    public Result<StorageObject> completeChunk(
            @RequestParam("uploadId") String uploadId,
            @RequestParam("path") String path,
            @RequestParam("totalChunks") int totalChunks) {
        StorageObject object = storageTemplate.completeChunkedUpload(uploadId, path, totalChunks);
        return Result.success(object);
    }

    /**
     * 中止分片上传会话并递归清理所有碎片
     *
     * @param uploadId 分片上传会话标识
     * @return 统一成功契约
     */
    @PostMapping("/chunk/abort")
    public Result<Void> abortChunk(@RequestParam("uploadId") String uploadId) {
        storageTemplate.abortChunkedUpload(uploadId);
        return Result.success();
    }
}
