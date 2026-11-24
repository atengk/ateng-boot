package io.github.atengk.boot.sample.storage.controller;

import io.github.atengk.boot.core.code.CommonResultCode;
import io.github.atengk.boot.core.exception.BusinessException;
import io.github.atengk.boot.core.model.Result;
import io.github.atengk.boot.storage.driver.LocalDriver;
import io.github.atengk.boot.storage.driver.StorageDriver;
import io.github.atengk.boot.storage.factory.StorageDriverFactory;
import io.github.atengk.boot.storage.model.PresignedDownloadRequest;
import io.github.atengk.boot.storage.model.PresignedUploadRequest;
import io.github.atengk.boot.storage.model.PresignedUrl;
import io.github.atengk.boot.storage.model.StorageObject;
import io.github.atengk.boot.storage.template.StorageOperations;
import io.github.atengk.boot.storage.template.StorageTemplate;
import io.github.atengk.boot.storage.util.StorageSecurityUtil;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.io.InputStream;
import java.time.Duration;
import java.time.Instant;

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
    private final StorageDriverFactory driverFactory;

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

    /**
     * 上层自建受控直传接口示例（适用于关闭内置 upload-endpoint-enabled=false 后由业务层自主鉴权与验签的场景）
     *
     * @param department  模拟调用方所属部门编码（用于校验是否有权写入目标部门目录）
     * @param path        存储相对路径（要求以 "contracts/{department}/" 起始）
     * @param expires     Unix 秒级过期时间戳
     * @param signature   HMAC-SHA256 预签名摘要
     * @param contentType 限定的内容类型（可选）
     * @param maxBytes    允许上传的最大字节数（可选，默认 -1）
     * @param source      目标存储源标识（可选）
     * @param request     HTTP 请求对象
     * @return 存储元数据契约
     * @throws IOException IO 读取异常
     */
    @PutMapping("/custom-upload")
    public Result<StorageObject> customAuthorizedUpload(
            @RequestHeader(value = "X-Dept-Code", defaultValue = "finance") String department,
            @RequestParam("path") @NotBlank(message = "目标路径不能为空") String path,
            @RequestParam("expires") long expires,
            @RequestParam("signature") @NotBlank(message = "签名不能为空") String signature,
            @RequestParam(value = "contentType", required = false) String contentType,
            @RequestParam(value = "maxBytes", defaultValue = "-1") long maxBytes,
            @RequestParam(value = "source", required = false) String source,
            HttpServletRequest request) throws IOException {
        // 1. 路径清洗与时效校验
        String cleanPath = StorageSecurityUtil.normalizePath(path);
        if (Instant.now().getEpochSecond() > expires) {
            throw new BusinessException(CommonResultCode.FORBIDDEN, "自定义直传外链已过期");
        }

        // 2. 自定义业务权限校验：仅允许写入归属部门目录
        String requiredPrefix = "contracts/" + department.trim() + "/";
        if (!cleanPath.startsWith(requiredPrefix)) {
            throw new BusinessException(CommonResultCode.FORBIDDEN,
                    "无权上传至非所属部门目录: expectedPrefix=" + requiredPrefix);
        }

        // 3. 复用 StorageSecurityUtil 对称载荷规则执行 HMAC-SHA256 验签
        StorageDriver driver = driverFactory.getDriver(source);
        if (!(driver instanceof LocalDriver localDriver)) {
            throw new BusinessException(CommonResultCode.BAD_REQUEST, "仅支持本地存储源自定义签名校验");
        }
        String normalizedSource = (source != null && !source.isBlank()) ? source.trim() : null;
        String payload = StorageSecurityUtil.buildPresignedUploadSignaturePayload(
                cleanPath, expires, contentType, maxBytes, normalizedSource);
        if (!StorageSecurityUtil.verifyHmacSha256(payload, localDriver.getSecretKey(), signature)) {
            throw new BusinessException(CommonResultCode.FORBIDDEN, "自定义直传签名校验失败");
        }

        // 4. 校验限定 Content-Type 与 Content-Length 配额（防篡改与超限写入）
        String incomingContentType = request.getContentType();
        if (contentType != null && !contentType.isBlank()) {
            String actualMime = incomingContentType != null ? incomingContentType.split(";")[0].trim() : "";
            if (!actualMime.equalsIgnoreCase(contentType.trim())) {
                throw new BusinessException(CommonResultCode.BAD_REQUEST, "上传 Content-Type 与预签名限定不一致");
            }
        }
        long contentLength = request.getContentLengthLong();
        if (maxBytes > 0 && contentLength > maxBytes) {
            throw new BusinessException(CommonResultCode.BAD_REQUEST, "上传字节数超出预签名允许上限");
        }

        // 5. 校验通过后委托 StorageTemplate 写入存储
        try (InputStream in = request.getInputStream()) {
            StorageObject object = getOperations(source).upload(
                    cleanPath, in, incomingContentType, contentLength);
            return Result.success(object);
        }
    }

    private StorageOperations getOperations(String source) {
        return (source != null && !source.isBlank()) ? storageTemplate.use(source) : storageTemplate;
    }
}
