package io.github.atengk.boot.sample.storage.controller;

import io.github.atengk.boot.core.model.Result;
import io.github.atengk.boot.storage.model.PresignedDownloadRequest;
import io.github.atengk.boot.storage.model.PresignedUploadRequest;
import io.github.atengk.boot.storage.model.PresignedUrl;
import io.github.atengk.boot.storage.model.StorageObject;
import io.github.atengk.boot.storage.template.StorageOperations;
import io.github.atengk.boot.storage.template.StorageTemplate;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
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
 * 预签名外链、客户端安全直传与公共读永久直链控制器
 * 演示 Local HMAC-SHA256 与云端 S3 预签名只读外链、友好中文文件名重写、客户端 HTTP PUT 预签名直传、
 * 公开素材公共读授权直传以及三级拓扑自适应永久直链
 *
 * @author Ateng
 * @since 2026-11-08
 */
@Slf4j
@Validated
@RestController
@RequestMapping("/sample/storage/presigned")
@RequiredArgsConstructor
public class StoragePresignedController {

    private final StorageTemplate storageTemplate;

    /**
     * 获取指定存储对象的预签名只读防盗链下载外链
     *
     * @param path            目标对象相对路径
     * @param durationSeconds 外链有效时长（秒，默认 300 秒）
     * @param source          目标存储源标识（可选，默认当前活动源）
     * @return 包含 URL 与过期时间的预签名凭据
     */
    @GetMapping("/download-url")
    public Result<PresignedUrl> getPresignedDownloadUrl(
            @RequestParam("path") @NotBlank(message = "文件路径不能为空") String path,
            @RequestParam(value = "durationSeconds", defaultValue = "300") @Min(value = 1, message = "有效时长必须大于 0") long durationSeconds,
            @RequestParam(value = "source", required = false) String source) {
        StorageOperations ops = getOperations(source);
        PresignedUrl presignedUrl = ops.getPresignedUrl(path, Duration.ofSeconds(durationSeconds));
        return Result.success(presignedUrl);
    }

    /**
     * 获取带友好中文下载文件名的预签名安全下载外链（基于 RFC 5987 / RFC 6266 重写 response-content-disposition）
     *
     * @param path            目标对象相对路径
     * @param filename        客户端下载弹出的友好业务文件名
     * @param durationSeconds 外链有效时长（秒，默认 300 秒）
     * @param source          目标存储源标识（默认 s3）
     * @return 包含响应头重写参数的预签名凭据
     */
    @GetMapping("/friendly-download-url")
    public Result<PresignedUrl> getFriendlyDownloadUrl(
            @RequestParam("path") @NotBlank(message = "文件路径不能为空") String path,
            @RequestParam("filename") @NotBlank(message = "下载文件名不能为空") String filename,
            @RequestParam(value = "durationSeconds", defaultValue = "300") @Min(value = 1, message = "有效时长必须大于 0") long durationSeconds,
            @RequestParam(value = "source", defaultValue = "s3") String source) {
        StorageOperations ops = getOperations(source);
        PresignedDownloadRequest request = PresignedDownloadRequest.builder()
                .path(path)
                .duration(Duration.ofSeconds(durationSeconds))
                .downloadFilename(filename)
                .build();
        PresignedUrl presignedUrl = ops.getPresignedUrl(request);
        return Result.success(presignedUrl);
    }

    /**
     * 获取客户端 HTTP PUT 预签名安全直传写入外链（支持限定 MIME 类型与最大字节配额）
     *
     * @param path            目标对象相对路径
     * @param durationSeconds 外链有效时长（秒，默认 300 秒）
     * @param contentType     严格限定直传内容类型（可选）
     * @param maxBytes        允许上传最大字节大小（可选，-1 表示不限制）
     * @param source          目标存储源标识（可选，默认当前活动源）
     * @return 预签名直传凭据（前端可直接向该 URL 发起 HTTP PUT 请求）
     */
    @GetMapping("/upload-url")
    public Result<PresignedUrl> getPresignedUploadUrl(
            @RequestParam("path") @NotBlank(message = "目标路径不能为空") String path,
            @RequestParam(value = "durationSeconds", defaultValue = "300") @Min(value = 1, message = "有效期必须大于 0") long durationSeconds,
            @RequestParam(value = "contentType", required = false) String contentType,
            @RequestParam(value = "maxBytes", defaultValue = "-1") long maxBytes,
            @RequestParam(value = "source", required = false) String source) {
        StorageOperations ops = getOperations(source);
        PresignedUploadRequest request = PresignedUploadRequest.builder()
                .path(path)
                .duration(Duration.ofSeconds(durationSeconds))
                .contentType(contentType)
                .maxSizeBytes(maxBytes)
                .build();
        PresignedUrl presignedUrl = ops.getPresignedUploadUrl(request);
        return Result.success(presignedUrl);
    }

    /**
     * 上传公开宣传素材并赋予公共读权限（挂载 Canned ACL: public-read）
     *
     * @param file   上传的公开表单文件
     * @param path   可选存储相对路径（为空使用原始文件名）
     * @param source 目标存储源标识（默认 s3）
     * @return 存储元数据契约
     * @throws IOException IO 读取异常
     */
    @PostMapping("/upload-public")
    public Result<StorageObject> uploadPublic(
            @RequestParam("file") @NotNull(message = "上传文件不能为空") MultipartFile file,
            @RequestParam(value = "path", required = false) String path,
            @RequestParam(value = "source", defaultValue = "s3") String source) throws IOException {
        String targetPath = (path != null && !path.isBlank()) ? path : file.getOriginalFilename();
        try (InputStream in = file.getInputStream()) {
            StorageObject object = storageTemplate.use(source).uploadPublic(targetPath, in, file.getContentType(), file.getSize());
            return Result.success(object);
        }
    }

    /**
     * 获取公开桶永久直接访问 URL（三级拓扑自适应：自定义 CDN 域名 -> Path-Style -> Virtual-Host Style，不含临时签名字段）
     *
     * @param path   目标对象相对路径
     * @param source 目标存储源标识（默认 s3）
     * @return 永久直接访问 URL
     */
    @GetMapping("/public-url")
    public Result<String> getPublicUrl(
            @RequestParam("path") @NotBlank(message = "文件路径不能为空") String path,
            @RequestParam(value = "source", defaultValue = "s3") String source) {
        String url = getOperations(source).getUrl(path);
        return Result.success(url);
    }

    private StorageOperations getOperations(String source) {
        return (source != null && !source.isBlank()) ? storageTemplate.use(source) : storageTemplate;
    }
}
