package io.github.atengk.boot.storage.template;

import io.github.atengk.boot.core.model.PageQuery;
import io.github.atengk.boot.core.model.PageResult;
import io.github.atengk.boot.core.util.FileUtil;
import io.github.atengk.boot.storage.context.StorageContextHolder;
import io.github.atengk.boot.storage.driver.StorageDriver;
import io.github.atengk.boot.storage.exception.StorageException;
import io.github.atengk.boot.storage.exception.StorageResultCode;
import io.github.atengk.boot.storage.event.StorageDeletedEvent;
import io.github.atengk.boot.storage.event.StorageEvent;
import io.github.atengk.boot.storage.event.StorageUploadedEvent;
import io.github.atengk.boot.storage.factory.StorageDriverFactory;
import io.github.atengk.boot.storage.model.PresignedDownloadRequest;
import io.github.atengk.boot.storage.model.PresignedUploadRequest;
import io.github.atengk.boot.storage.model.PresignedUrl;
import io.github.atengk.boot.storage.model.StorageObject;
import io.github.atengk.boot.core.util.JsonUtil;
import io.github.atengk.boot.storage.guard.StorageGuard;
import io.github.atengk.boot.storage.resource.StorageResource;
import io.github.atengk.boot.storage.util.StorageSecurityUtil;
import jakarta.servlet.http.HttpServletResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationEvent;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.ApplicationEventPublisherAware;
import org.springframework.web.multipart.MultipartFile;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.URLEncoder;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.AbstractMap;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/**
 * 存储操作核心受管高阶门面
 * 负责参数清洗、Web 文件适配、Spring 资源桥接、生命周期事件广播与多存储源路由调度
 *
 * @author Ateng
 * @since 2026-10-17
 */
@Slf4j
public class StorageTemplate implements StorageOperations, ApplicationEventPublisherAware {

    private final StorageDriver defaultDriver;

    private final StorageDriverFactory factory;

    private final Map<String, StorageDriver> driverMap;

    private final boolean bound;

    private final String boundSourceKey;

    private ApplicationEventPublisher eventPublisher;

    private StorageGuard storageGuard;

    /**
     * 基于单一主驱动构造门面
     *
     * @param defaultDriver 默认存储驱动
     */
    public StorageTemplate(StorageDriver defaultDriver) {
        this(defaultDriver, null, Collections.emptyMap(), false, null);
    }

    /**
     * 基于存储工厂构造门面（以工厂 primary 驱动作为默认主驱动）
     *
     * @param factory 驱动工厂
     */
    public StorageTemplate(StorageDriverFactory factory) {
        this(factory != null ? factory.getPrimaryDriver() : null, factory,
                factory != null ? factory.getAllDrivers() : Collections.emptyMap(), false, null);
    }

    /**
     * 基于主驱动与存储工厂构造门面（推荐多源构造）
     *
     * @param defaultDriver 默认主存储驱动
     * @param factory       驱动工厂
     */
    public StorageTemplate(StorageDriver defaultDriver, StorageDriverFactory factory) {
        this(defaultDriver, factory, factory != null ? factory.getAllDrivers() : Collections.emptyMap(), false, null);
    }

    /**
     * 基于主驱动与多命名驱动映射构造门面
     *
     * @param defaultDriver 默认主存储驱动
     * @param driverMap     命名存储驱动字典
     */
    public StorageTemplate(StorageDriver defaultDriver, Map<String, StorageDriver> driverMap) {
        this(defaultDriver, null, driverMap, false, null);
    }

    /**
     * 完整全参构造函数（兼容历史调用）
     *
     * @param defaultDriver 默认主存储驱动
     * @param factory       驱动工厂（可选）
     * @param driverMap     命名存储驱动字典
     * @param bound         是否为显式绑定的独立句柄
     */
    public StorageTemplate(StorageDriver defaultDriver, StorageDriverFactory factory,
                           Map<String, StorageDriver> driverMap, boolean bound) {
        this(defaultDriver, factory, driverMap, bound, null);
    }

    /**
     * 完整全参构造函数（支持指定绑定的存储源名称）
     *
     * @param defaultDriver  默认主存储驱动
     * @param factory        驱动工厂（可选）
     * @param driverMap      命名存储驱动字典
     * @param bound          是否为显式绑定的独立句柄
     * @param boundSourceKey 绑定的存储源标识
     */
    public StorageTemplate(StorageDriver defaultDriver, StorageDriverFactory factory,
                           Map<String, StorageDriver> driverMap, boolean bound, String boundSourceKey) {
        if (defaultDriver == null) {
            throw new IllegalArgumentException("Default StorageDriver must not be null");
        }
        this.defaultDriver = defaultDriver;
        this.factory = factory;
        this.driverMap = (driverMap != null && !driverMap.isEmpty())
                ? new HashMap<>(driverMap)
                : Collections.emptyMap();
        this.bound = bound;
        this.boundSourceKey = boundSourceKey;
    }

    @Override
    public void setApplicationEventPublisher(ApplicationEventPublisher applicationEventPublisher) {
        this.eventPublisher = applicationEventPublisher;
    }

    /**
     * 编程式链式切源：获取指定命名存储源的不可变操作契约
     * 返回绑定目标驱动的独立句柄，局部作用域线程安全，不污染全局线程上下文
     *
     * @param sourceKey 存储源标识（如 "local", "oss-public", "minio-secure"）
     * @return 绑定目标存储源的操作门面实例
     */
    @Override
    public StorageOperations use(String sourceKey) {
        if (sourceKey == null || sourceKey.isBlank()
                || "primary".equalsIgnoreCase(sourceKey)
                || "default".equalsIgnoreCase(sourceKey)) {
            if (this.bound) {
                StorageDriver primaryDriver = this.factory != null ? this.factory.getPrimaryDriver() : this.defaultDriver;
                StorageTemplate sub = new StorageTemplate(primaryDriver, this.factory, this.driverMap, true, "primary");
                sub.setApplicationEventPublisher(this.eventPublisher);
                sub.setStorageGuard(this.storageGuard);
                return sub;
            }
            return this;
        }
        StorageDriver targetDriver = null;
        if (this.factory != null) {
            targetDriver = this.factory.getDriver(sourceKey);
        } else if (this.driverMap != null) {
            targetDriver = this.driverMap.get(sourceKey.trim());
        }
        if (targetDriver == null) {
            throw new StorageException(StorageResultCode.STORAGE_SOURCE_NOT_FOUND, "未找到指定的存储源: " + sourceKey);
        }
        StorageTemplate sub = new StorageTemplate(targetDriver, this.factory, this.driverMap, true, sourceKey.trim());
        sub.setApplicationEventPublisher(this.eventPublisher);
        sub.setStorageGuard(this.storageGuard);
        return sub;
    }

    @Override
    public StorageObject upload(String path, InputStream in) {
        return upload(path, in, null, -1);
    }

    @Override
    public StorageObject upload(String path, InputStream in, String contentType) {
        return upload(path, in, contentType, -1);
    }

    @Override
    public StorageObject upload(String path, InputStream in, String contentType, long size) {
        return upload(path, in, contentType, size, null);
    }

    @Override
    public StorageObject upload(String path, InputStream in, String contentType, long size, Map<String, String> userMetadata) {
        return upload(path, in, contentType, size, userMetadata, false);
    }

    @Override
    public StorageObject upload(String path, InputStream in, String contentType, long size, Map<String, String> userMetadata, boolean publicRead) {
        InputStream effectiveStream = in;
        String effectiveContentType = contentType;
        if (this.storageGuard != null && this.storageGuard.isEnabled()) {
            StorageGuard.GuardResult guardResult = this.storageGuard.inspect(path, in, contentType);
            effectiveStream = guardResult.getGuardedStream();
            if ((effectiveContentType == null || effectiveContentType.isBlank() || "application/octet-stream".equals(effectiveContentType))
                    && guardResult.getDetectedMimeType() != null) {
                effectiveContentType = guardResult.getDetectedMimeType();
            }
        }
        StorageObject metadata = getDriver().upload(path, effectiveStream, effectiveContentType, size, userMetadata, publicRead);
        if (metadata != null) {
            publishEventSafely(new StorageUploadedEvent(this, getCurrentSourceKey(), metadata.getPath(), metadata.getSize(), metadata.getContentType()));
        }
        return metadata;
    }

    @Override
    public StorageObject uploadPublic(String path, InputStream in) {
        return uploadPublic(path, in, null, -1);
    }

    @Override
    public StorageObject uploadPublic(String path, InputStream in, String contentType) {
        return uploadPublic(path, in, contentType, -1);
    }

    @Override
    public StorageObject uploadPublic(String path, InputStream in, String contentType, long size) {
        return upload(path, in, contentType, size, null, true);
    }

    @Override
    public StorageObject uploadPublic(String path, MultipartFile file) {
        return uploadMultipartFile(path, file, null, true);
    }

    @Override
    public StorageObject upload(String path, MultipartFile file) {
        return upload(path, file, null);
    }

    @Override
    public StorageObject upload(String path, MultipartFile file, Map<String, String> userMetadata) {
        return uploadMultipartFile(path, file, userMetadata, false);
    }

    private StorageObject uploadMultipartFile(String path, MultipartFile file, Map<String, String> userMetadata, boolean publicRead) {
        if (file == null || file.isEmpty()) {
            throw new StorageException(StorageResultCode.UPLOAD_FAILED, "上传文件不能为空");
        }
        String targetPath = path;
        String originalFilename = file.getOriginalFilename();
        if (targetPath == null || targetPath.isBlank()) {
            targetPath = originalFilename;
        } else if (targetPath.endsWith("/") || targetPath.endsWith("\\")) {
            targetPath = targetPath + FileUtil.cleanFilename(originalFilename);
        }

        try (InputStream in = file.getInputStream()) {
            return upload(targetPath, in, file.getContentType(), file.getSize(), userMetadata, publicRead);
        } catch (IOException e) {
            throw new StorageException(StorageResultCode.UPLOAD_FAILED, "读取 MultipartFile 输入流失败: " + originalFilename, e);
        }
    }

    @Override
    public InputStream download(String path) {
        return getDriver().download(path);
    }

    @Override
    public InputStream download(String path, long startOffset, long length) {
        if (startOffset < 0) {
            throw new StorageException(StorageResultCode.OPERATION_FAILED, "下载起始偏移量不能为负数: " + startOffset);
        }
        if (length <= 0) {
            throw new StorageException(StorageResultCode.OPERATION_FAILED, "下载读取长度必须大于 0: " + length);
        }
        return getDriver().download(path, startOffset, length);
    }

    @Override
    public StorageResource getResource(String path) {
        return new StorageResource(path, getDriver());
    }

    @Override
    public StorageObject getMetadata(String path) {
        return getDriver().getMetadata(path);
    }

    @Override
    public Map<String, String> getObjectTags(String path) {
        if (path == null || path.isBlank()) {
            throw new StorageException(StorageResultCode.INVALID_PATH, "存储路径不能为空");
        }
        return getDriver().getObjectTags(path);
    }

    @Override
    public void setObjectTags(String path, Map<String, String> tags) {
        if (path == null || path.isBlank()) {
            throw new StorageException(StorageResultCode.INVALID_PATH, "存储路径不能为空");
        }
        if (tags == null || tags.isEmpty()) {
            throw new StorageException(StorageResultCode.OPERATION_FAILED, "设置对象标签不能为空");
        }
        getDriver().setObjectTags(path, tags);
    }

    @Override
    public void deleteObjectTags(String path) {
        if (path == null || path.isBlank()) {
            throw new StorageException(StorageResultCode.INVALID_PATH, "存储路径不能为空");
        }
        getDriver().deleteObjectTags(path);
    }

    @Override
    public boolean exists(String path) {
        return getDriver().exists(path);
    }

    @Override
    public boolean delete(String path) {
        boolean result = getDriver().delete(path);
        if (result) {
            publishEventSafely(new StorageDeletedEvent(this, getCurrentSourceKey(), StorageSecurityUtil.normalizePath(path)));
        }
        return result;
    }

    @Override
    public boolean deleteBatch(List<String> paths) {
        boolean result = getDriver().deleteBatch(paths);
        if (result && paths != null) {
            for (String p : paths) {
                publishEventSafely(new StorageDeletedEvent(this, getCurrentSourceKey(), StorageSecurityUtil.normalizePath(p)));
            }
        }
        return result;
    }

    @Override
    public boolean deletePrefix(String prefix) {
        boolean result = getDriver().deletePrefix(prefix);
        if (result && prefix != null && !prefix.isBlank()) {
            publishEventSafely(new StorageDeletedEvent(this, getCurrentSourceKey(), prefix.trim()));
        }
        return result;
    }

    @Override
    public boolean deleteDirectory(String dirPath) {
        boolean result = getDriver().deleteDirectory(dirPath);
        if (result && dirPath != null && !dirPath.isBlank()) {
            publishEventSafely(new StorageDeletedEvent(this, getCurrentSourceKey(), dirPath.trim()));
        }
        return result;
    }

    @Override
    public boolean copy(String sourcePath, String targetPath) {
        boolean result = getDriver().copy(sourcePath, targetPath);
        if (result) {
            publishUploadedEventSafely(targetPath);
        }
        return result;
    }

    @Override
    public boolean move(String sourcePath, String targetPath) {
        boolean result = getDriver().move(sourcePath, targetPath);
        if (result) {
            publishUploadedEventSafely(targetPath);
            publishEventSafely(new StorageDeletedEvent(this, getCurrentSourceKey(), StorageSecurityUtil.normalizePath(sourcePath)));
        }
        return result;
    }

    private void publishUploadedEventSafely(String targetPath) {
        StorageObject meta = null;
        try {
            meta = getMetadata(targetPath);
        } catch (Exception ignored) {
        }
        Long size = meta != null ? meta.getSize() : -1L;
        String contentType = meta != null ? meta.getContentType() : null;
        publishEventSafely(new StorageUploadedEvent(this, getCurrentSourceKey(), StorageSecurityUtil.normalizePath(targetPath), size, contentType));
    }

    @Override
    public StorageObject uploadString(String path, String content, Charset charset) {
        if (content == null) {
            throw new StorageException(StorageResultCode.UPLOAD_FAILED, "上传文本内容不能为空");
        }
        Charset effectiveCharset = charset != null ? charset : StandardCharsets.UTF_8;
        byte[] bytes = content.getBytes(effectiveCharset);
        String contentType = "text/plain;charset=" + effectiveCharset.name();
        return upload(path, new ByteArrayInputStream(bytes), contentType, bytes.length);
    }

    @Override
    public String downloadString(String path, Charset charset) {
        Charset effectiveCharset = charset != null ? charset : StandardCharsets.UTF_8;
        byte[] bytes = downloadBytes(path);
        return new String(bytes, effectiveCharset);
    }

    @Override
    public StorageObject uploadBytes(String path, byte[] bytes, String contentType) {
        if (bytes == null) {
            throw new StorageException(StorageResultCode.UPLOAD_FAILED, "上传字节数组不能为空");
        }
        return upload(path, new ByteArrayInputStream(bytes), contentType, bytes.length);
    }

    @Override
    public byte[] downloadBytes(String path) {
        try (InputStream in = download(path)) {
            if (in == null) {
                throw new StorageException(StorageResultCode.DOWNLOAD_FAILED, "读取存储对象输入流为空: " + path);
            }
            return in.readAllBytes();
        } catch (IOException e) {
            throw new StorageException(StorageResultCode.DOWNLOAD_FAILED, "读取对象字节流失败: " + path, e);
        }
    }

    @Override
    public StorageObject uploadJson(String path, Object pojo) {
        if (pojo == null) {
            throw new StorageException(StorageResultCode.UPLOAD_FAILED, "上传 JSON 对象不能为空");
        }
        byte[] bytes = JsonUtil.toJsonBytes(pojo);
        return upload(path, new ByteArrayInputStream(bytes), "application/json;charset=UTF-8", bytes.length);
    }

    @Override
    public <T> T downloadJson(String path, Class<T> clazz) {
        if (clazz == null) {
            throw new StorageException(StorageResultCode.OPERATION_FAILED, "反序列化目标 Class 不能为空");
        }
        byte[] bytes = downloadBytes(path);
        return JsonUtil.parseObject(bytes, clazz);
    }

    @Override
    public List<StorageObject> list(String prefix, boolean recursive) {
        return getDriver().list(prefix, recursive);
    }

    @Override
    public PageResult<StorageObject> listPage(String prefix, PageQuery pageQuery, boolean recursive) {
        long current = (pageQuery != null && pageQuery.getCurrent() >= 1) ? pageQuery.getCurrent() : 1L;
        long size = (pageQuery != null && pageQuery.getSize() >= 1) ? pageQuery.getSize() : 10L;

        List<StorageObject> all = list(prefix, recursive);
        long total = all.size();
        if (total == 0) {
            return PageResult.of(Collections.emptyList(), 0, current, size);
        }

        long skip = (current - 1) * size;
        List<StorageObject> pageRecords = all.stream()
                .skip(skip)
                .limit(size)
                .toList();

        return PageResult.of(pageRecords, total, current, size);
    }

    @Override
    public String getUrl(String path) {
        return getDriver().getUrl(path);
    }

    @Override
    public PresignedUrl getPresignedUrl(String path, Duration duration) {
        return getDriver().getPresignedUrl(path, duration);
    }

    @Override
    public PresignedUrl getPresignedUrl(String path, Duration duration, String downloadFilename) {
        return getDriver().getPresignedUrl(path, duration, downloadFilename);
    }

    @Override
    public PresignedUrl getPresignedUrl(PresignedDownloadRequest request) {
        if (request == null) {
            throw new StorageException(StorageResultCode.OPERATION_FAILED, "预签名下载请求不能为空");
        }
        return getDriver().getPresignedUrl(request);
    }

    @Override
    public PresignedUrl getPresignedUploadUrl(String path, Duration duration) {
        return getDriver().getPresignedUploadUrl(path, duration);
    }

    @Override
    public PresignedUrl getPresignedUploadUrl(PresignedUploadRequest request) {
        if (request == null) {
            throw new StorageException(StorageResultCode.OPERATION_FAILED, "预签名直传请求不能为空");
        }
        return getDriver().getPresignedUploadUrl(request);
    }

    @Override
    public PresignedUrl getPresignedUploadPartUrl(String path, String uploadId, int chunkIndex, Duration duration) {
        return getDriver().getPresignedUploadPartUrl(path, uploadId, chunkIndex, duration);
    }

    @Override
    public void downloadAsZip(List<String> paths, OutputStream out) {
        if (paths == null || paths.isEmpty()) {
            throw new StorageException(StorageResultCode.OPERATION_FAILED, "打包 ZIP 路径列表不能为空");
        }
        List<Map.Entry<String, String>> entries = new ArrayList<>();
        for (String path : paths) {
            if (path != null && !path.isBlank()) {
                entries.add(new AbstractMap.SimpleImmutableEntry<>(path, path));
            }
        }
        downloadAsZipInternal(entries, out);
    }

    @Override
    public void downloadAsZip(Map<String, String> pathMap, OutputStream out) {
        if (pathMap == null || pathMap.isEmpty()) {
            throw new StorageException(StorageResultCode.OPERATION_FAILED, "打包 ZIP 路径映射表不能为空");
        }
        List<Map.Entry<String, String>> entries = new ArrayList<>(pathMap.entrySet());
        downloadAsZipInternal(entries, out);
    }

    private void downloadAsZipInternal(List<Map.Entry<String, String>> entries, OutputStream out) {
        if (entries == null || entries.isEmpty()) {
            throw new StorageException(StorageResultCode.OPERATION_FAILED, "打包 ZIP 条目列表不能为空");
        }
        if (out == null) {
            throw new StorageException(StorageResultCode.OPERATION_FAILED, "打包 ZIP 输出流不能为空");
        }

        // 1. 严格防御（Q3-A）：前置检查所有文件必须存在，缺失直接抛出 OBJECT_NOT_FOUND 异常
        for (Map.Entry<String, String> entry : entries) {
            String path = entry.getKey();
            if (!exists(path)) {
                throw new StorageException(StorageResultCode.OBJECT_NOT_FOUND, "打包 ZIP 失败，指定文件不存在: " + path);
            }
        }

        // 2. 纯流式压缩写出
        try (ZipOutputStream zos = new ZipOutputStream(out, StandardCharsets.UTF_8)) {
            Set<String> usedEntryNames = new HashSet<>();
            for (Map.Entry<String, String> entry : entries) {
                String path = entry.getKey();
                String rawEntryName = (entry.getValue() != null && !entry.getValue().isBlank())
                        ? entry.getValue()
                        : path;
                String entryName = resolveUniqueZipEntryName(rawEntryName, usedEntryNames);

                ZipEntry zipEntry = new ZipEntry(entryName);
                zos.putNextEntry(zipEntry);
                try (InputStream in = download(path)) {
                    if (in == null) {
                        throw new StorageException(StorageResultCode.DOWNLOAD_FAILED, "读取存储对象输入流为空: " + path);
                    }
                    in.transferTo(zos);
                }
                zos.closeEntry();
            }
            zos.finish();
        } catch (IOException e) {
            throw new StorageException(StorageResultCode.OPERATION_FAILED, "流式生成 ZIP 压缩包失败: " + e.getMessage(), e);
        }
    }

    @Override
    public void downloadAsZip(List<String> paths, HttpServletResponse response, String zipFilename) {
        if (paths == null || paths.isEmpty()) {
            throw new StorageException(StorageResultCode.OPERATION_FAILED, "打包 ZIP 路径列表不能为空");
        }
        prepareZipResponse(response, zipFilename);
        try {
            downloadAsZip(paths, response.getOutputStream());
        } catch (IOException e) {
            throw new StorageException(StorageResultCode.OPERATION_FAILED, "获取 HTTP 响应输出流失败: " + e.getMessage(), e);
        }
    }

    @Override
    public void downloadAsZip(Map<String, String> pathMap, HttpServletResponse response, String zipFilename) {
        if (pathMap == null || pathMap.isEmpty()) {
            throw new StorageException(StorageResultCode.OPERATION_FAILED, "打包 ZIP 路径映射表不能为空");
        }
        prepareZipResponse(response, zipFilename);
        try {
            downloadAsZip(pathMap, response.getOutputStream());
        } catch (IOException e) {
            throw new StorageException(StorageResultCode.OPERATION_FAILED, "获取 HTTP 响应输出流失败: " + e.getMessage(), e);
        }
    }

    private void prepareZipResponse(HttpServletResponse response, String zipFilename) {
        if (response == null) {
            throw new StorageException(StorageResultCode.OPERATION_FAILED, "HttpServletResponse 不能为空");
        }
        String finalFilename = (zipFilename != null && !zipFilename.isBlank()) ? zipFilename.trim() : "archive.zip";
        if (!finalFilename.toLowerCase().endsWith(".zip")) {
            finalFilename = finalFilename + ".zip";
        }
        response.setContentType("application/zip");
        String encodedFilename = URLEncoder.encode(finalFilename, StandardCharsets.UTF_8).replace("+", "%20");
        response.setHeader("Content-Disposition", "attachment; filename=\"" + encodedFilename + "\"; filename*=UTF-8''" + encodedFilename);
    }

    private String resolveUniqueZipEntryName(String rawEntryName, Set<String> usedEntryNames) {
        String sanitized = rawEntryName.replace('\\', '/');
        while (sanitized.startsWith("/")) {
            sanitized = sanitized.substring(1);
        }
        if (sanitized.isBlank()) {
            sanitized = "file";
        }
        if (usedEntryNames.add(sanitized)) {
            return sanitized;
        }
        int dotIndex = sanitized.lastIndexOf('.');
        String baseName = dotIndex > 0 ? sanitized.substring(0, dotIndex) : sanitized;
        String ext = dotIndex > 0 ? sanitized.substring(dotIndex) : "";
        int counter = 1;
        String candidate;
        do {
            candidate = baseName + " (" + counter + ")" + ext;
            counter++;
        } while (!usedEntryNames.add(candidate));
        return candidate;
    }

    public String getCurrentSourceKey() {
        if (this.bound && this.boundSourceKey != null && !this.boundSourceKey.isBlank()) {
            return this.boundSourceKey;
        }
        String contextSource = StorageContextHolder.get();
        if (contextSource != null && !contextSource.isBlank()) {
            return contextSource;
        }
        if (this.factory != null && this.factory.getPrimary() != null) {
            return this.factory.getPrimary();
        }
        return "primary";
    }

    protected void publishEventSafely(ApplicationEvent event) {
        if (this.eventPublisher == null || event == null) {
            return;
        }
        try {
            this.eventPublisher.publishEvent(event);
        } catch (Throwable ex) {
            log.warn("发布存储生命周期事件失败或外部监听器执行异常 (Fail-Safe 隔离): event={}, error={}", event, ex.getMessage(), ex);
        }
    }

    /**
     * 获取当前活动驱动实例
     * 若当前处于 use() 产生的显式绑定句柄（bound=true），则恒定使用绑定的目标驱动；
     * 否则优先读取线程上下文 StorageContextHolder 指定的存储源，未设置时回退至 defaultDriver
     *
     * @return StorageDriver 实例
     */
    protected StorageDriver getDriver() {
        if (this.bound) {
            return this.defaultDriver;
        }
        String contextSource = StorageContextHolder.get();
        if (contextSource != null && !contextSource.isBlank()) {
            if (this.factory != null) {
                return this.factory.getDriver(contextSource);
            }
            StorageDriver driver = this.driverMap.get(contextSource.trim());
            if (driver != null) {
                return driver;
            }
            throw new StorageException(StorageResultCode.STORAGE_SOURCE_NOT_FOUND, "未找到上下文指定的存储源: " + contextSource);
        }
        return this.defaultDriver;
    }

    public StorageGuard getStorageGuard() {
        return this.storageGuard;
    }

    public void setStorageGuard(StorageGuard storageGuard) {
        this.storageGuard = storageGuard;
    }

    @Override
    public String initiateChunkedUpload(String path) {
        return getDriver().initiateChunkedUpload(path);
    }

    @Override
    public void uploadChunk(String uploadId, int chunkIndex, InputStream in, long size) {
        getDriver().uploadChunk(uploadId, chunkIndex, in, size);
    }

    @Override
    public StorageObject completeChunkedUpload(String uploadId, String targetPath, int totalChunks) {
        StorageObject obj = getDriver().completeChunkedUpload(uploadId, targetPath, totalChunks);
        if (obj != null) {
            publishEventSafely(new StorageUploadedEvent(this, getCurrentSourceKey(), obj.getPath(), obj.getSize(), obj.getContentType()));
        }
        return obj;
    }

    @Override
    public void abortChunkedUpload(String uploadId) {
        getDriver().abortChunkedUpload(uploadId);
    }

    @Override
    public int cleanExpiredChunkSessions(Duration maxAge) {
        return getDriver().cleanExpiredChunkSessions(maxAge);
    }

    @Override
    public boolean ping() {
        try {
            getDriver().ping();
            return true;
        } catch (Exception e) {
            log.warn("存储驱动连通性探测失败: {}", e.getMessage());
            return false;
        }
    }

    @Override
    public Map<String, Object> getDiagnostics() {
        return getDriver().getDiagnostics();
    }
}
