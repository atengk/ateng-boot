package io.github.atengk.boot.sample.storage.controller;

import io.github.atengk.boot.core.model.PageQuery;
import io.github.atengk.boot.core.model.PageResult;
import io.github.atengk.boot.core.model.Result;
import io.github.atengk.boot.sample.storage.service.FileStorageDemoService;
import io.github.atengk.boot.storage.model.HashAlgorithm;
import io.github.atengk.boot.storage.model.PresignedUploadRequest;
import io.github.atengk.boot.storage.model.PresignedUrl;
import io.github.atengk.boot.storage.model.StorageObject;
import io.github.atengk.boot.storage.resource.StorageResource;
import io.github.atengk.boot.storage.template.StorageTemplate;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.io.Resource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.annotation.Validated;
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
@Validated
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
            @RequestParam("file") @NotNull(message = "上传文件不能为空") MultipartFile file,
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
    public ResponseEntity<Resource> download(@RequestParam("path") @NotBlank(message = "下载路径不能为空") String path) throws IOException {
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
            @RequestParam("path") @NotBlank(message = "文件路径不能为空") String path,
            @RequestParam(value = "durationSeconds", defaultValue = "300") @Min(value = 1, message = "有效时长必须大于 0") long durationSeconds) {
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
            @RequestParam("file") @NotNull(message = "上传文件不能为空") MultipartFile file,
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
            @RequestParam("sourcePath") @NotBlank(message = "源路径不能为空") String sourcePath,
            @RequestParam("targetPath") @NotBlank(message = "目标路径不能为空") String targetPath) {
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
            @RequestParam("sourcePath") @NotBlank(message = "源路径不能为空") String sourcePath,
            @RequestParam("targetPath") @NotBlank(message = "目标路径不能为空") String targetPath) {
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
    public Result<Boolean> delete(@RequestParam("path") @NotBlank(message = "删除路径不能为空") String path) {
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
            @Valid @ModelAttribute PageQuery pageQuery,
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
            @RequestParam("path") @NotBlank(message = "存储路径不能为空") String path,
            @RequestParam("content") @NotNull(message = "文本内容不能为空") String content) {
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
    public Result<String> readString(@RequestParam("path") @NotBlank(message = "读取路径不能为空") String path) {
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
            @RequestParam("path") @NotBlank(message = "存储路径不能为空") String path,
            @NotNull(message = "JSON 载荷不能为空") @RequestBody Object data) {
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
    public Result<Map<String, Object>> readJson(@RequestParam("path") @NotBlank(message = "读取路径不能为空") String path) {
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
            @RequestParam("paths") @NotEmpty(message = "打包路径列表不能为空") List<@NotBlank(message = "打包路径不能为空") String> paths,
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
    public Result<String> initiateChunk(@RequestParam("path") @NotBlank(message = "目标存储路径不能为空") String path) {
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
            @RequestParam("uploadId") @NotBlank(message = "会话标识 uploadId 不能为空") String uploadId,
            @RequestParam("chunkIndex") @Min(value = 1, message = "分片序号必须从 1 开始") int chunkIndex,
            @RequestParam("file") @NotNull(message = "分片文件不能为空") MultipartFile file) throws IOException {
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
            @RequestParam("uploadId") @NotBlank(message = "会话标识 uploadId 不能为空") String uploadId,
            @RequestParam("path") @NotBlank(message = "目标存储路径不能为空") String path,
            @RequestParam("totalChunks") @Min(value = 1, message = "总片数必须 >= 1") int totalChunks) {
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
    public Result<Void> abortChunk(@RequestParam("uploadId") @NotBlank(message = "会话标识 uploadId 不能为空") String uploadId) {
        storageTemplate.abortChunkedUpload(uploadId);
        return Result.success();
    }

    /**
     * 获取指定存储对象的预签名安全直传写入外链凭据
     *
     * @param path            目标相对路径
     * @param durationSeconds 有效期（秒，默认 300 秒）
     * @param contentType     严格限定内容类型（可选）
     * @param maxBytes        允许上传最大字节大小（可选，-1 不限制）
     * @return 预签名直传外链
     */
    @GetMapping("/presigned-upload")
    public Result<PresignedUrl> getPresignedUploadUrl(
            @RequestParam("path") @NotBlank(message = "目标路径不能为空") String path,
            @RequestParam(value = "durationSeconds", defaultValue = "300") @Min(value = 1, message = "有效期必须大于 0") long durationSeconds,
            @RequestParam(value = "contentType", required = false) String contentType,
            @RequestParam(value = "maxBytes", defaultValue = "-1") long maxBytes) {
        PresignedUploadRequest request = PresignedUploadRequest.builder()
                .path(path)
                .duration(Duration.ofSeconds(durationSeconds))
                .contentType(contentType)
                .maxSizeBytes(maxBytes)
                .build();
        PresignedUrl presignedUrl = storageTemplate.getPresignedUploadUrl(request);
        return Result.success(presignedUrl);
    }

    /**
     * 获取大文件分片预签名安全直传写入外链凭据
     *
     * @param path            目标相对路径
     * @param uploadId        分片上传会话标识
     * @param chunkIndex      分片编号（1-based）
     * @param durationSeconds 有效期（秒，默认 300 秒）
     * @return 分片直传预签名外链
     */
    @GetMapping("/presigned-upload-part")
    public Result<PresignedUrl> getPresignedUploadPartUrl(
            @RequestParam("path") @NotBlank(message = "目标路径不能为空") String path,
            @RequestParam("uploadId") @NotBlank(message = "uploadId 不能为空") String uploadId,
            @RequestParam("chunkIndex") @Min(value = 1, message = "分片序号必须 >= 1") int chunkIndex,
            @RequestParam(value = "durationSeconds", defaultValue = "300") @Min(value = 1, message = "有效期必须大于 0") long durationSeconds) {
        PresignedUrl presignedUrl = storageTemplate.getPresignedUploadPartUrl(path, uploadId, chunkIndex, Duration.ofSeconds(durationSeconds));
        return Result.success(presignedUrl);
    }

    /**
     * 流式零拷贝计算已存储对象的散列哈希值（支持秒传唯一指纹比对）
     *
     * @param path      目标存储对象相对路径
     * @param algorithm 散列算法（MD5, SHA1, SHA256, CRC32，默认 SHA256）
     * @return 散列值字符串
     */
    @GetMapping("/hash")
    public Result<String> calculateHash(
            @RequestParam("path") @NotBlank(message = "目标路径不能为空") String path,
            @RequestParam(value = "algorithm", defaultValue = "SHA256") HashAlgorithm algorithm) {
        String hash = storageTemplate.calculateHash(path, algorithm);
        return Result.success(hash);
    }

    /**
     * 极速秒传业务接口：若哈希指纹池中存在该文件，直接完成零拷贝复用秒传
     *
     * @param targetPath 目标存放相对路径
     * @param hash       文件散列指纹
     * @return 存储元数据对象
     */
    @PostMapping("/fast-upload")
    public Result<StorageObject> fastUpload(
            @RequestParam("targetPath") @NotBlank(message = "目标路径不能为空") String targetPath,
            @RequestParam("hash") @NotBlank(message = "散列哈希不能为空") String hash) {
        String poolSourcePath = "hash-pool/" + hash + ".dat";
        if (!storageTemplate.exists(poolSourcePath)) {
            return Result.fail(SampleResultCode.FAST_UPLOAD_MISSED);
        }
        storageTemplate.copy(poolSourcePath, targetPath);
        StorageObject metadata = storageTemplate.getMetadata(targetPath);
        return Result.success(metadata);
    }

    /**
     * 样例工程业务响应状态码
     */
    public enum SampleResultCode implements io.github.atengk.boot.core.code.IResultCode {
        FAST_UPLOAD_MISSED(40404, "文件哈希未命中秒传池，请走普通上传流");

        private final Integer code;
        private final String msg;

        SampleResultCode(Integer code, String msg) {
            this.code = code;
            this.msg = msg;
        }

        @Override
        public Integer getCode() {
            return code;
        }

        @Override
        public String getMsg() {
            return msg;
        }
    }

    /**
     * 按前缀批量删除存储对象（流式批处理与防误删安全熔断）
     *
     * @param prefix 目标前缀
     * @return 操作结果
     */
    @PostMapping("/delete-prefix")
    public Result<Boolean> deletePrefix(@RequestParam("prefix") @NotBlank(message = "删除前缀不能为空") String prefix) {
        boolean success = storageTemplate.deletePrefix(prefix);
        return Result.success(success);
    }

    /**
     * 级联删除目录及其内部所有对象
     *
     * @param dirPath 目录路径
     * @return 操作结果
     */
    @PostMapping("/delete-directory")
    public Result<Boolean> deleteDirectory(@RequestParam("dirPath") @NotBlank(message = "目录路径不能为空") String dirPath) {
        boolean success = storageTemplate.deleteDirectory(dirPath);
        return Result.success(success);
    }

    /**
     * 上传文件并携带用户自定义元数据
     *
     * @param file         上传的表单文件
     * @param path         自定义存储路径
     * @param userMetadata 用户元数据字典
     * @return 存储元数据对象
     */
    @PostMapping("/upload-with-metadata")
    public Result<StorageObject> uploadWithMetadata(
            @RequestParam("file") @NotNull(message = "上传文件不能为空") MultipartFile file,
            @RequestParam("path") @NotBlank(message = "目标路径不能为空") String path,
            @RequestParam Map<String, String> userMetadata) {
        Map<String, String> customMeta = new java.util.HashMap<>(userMetadata);
        customMeta.remove("path");
        StorageObject object = storageTemplate.upload(path, file, customMeta);
        return Result.success(object);
    }
}
