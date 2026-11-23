package io.github.atengk.boot.sample.excel.storage;

import io.github.atengk.boot.excel.async.storage.ExportFileStorage;
import io.github.atengk.boot.excel.async.storage.LocalFileSystemStorage;
import io.github.atengk.boot.excel.properties.ExcelProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;

/**
 * 企业级自定义对象存储 (OSS) 分发 SPI 扩展演示实现
 * <p>
 * 演示通过扩展 {@link ExportFileStorage} 替换默认的 {@link LocalFileSystemStorage}，
 * 实现自定义云前缀 URL 分发（如 {@code https://oss.example.com/excel-exports/}）与存储接管。
 * 受 {@code ateng.boot.excel.sample.custom-storage.enabled=true} 条件化管控，默认关闭。
 * </p>
 *
 * @author Ateng
 * @since 2026-11-08
 */
@Component
@ConditionalOnProperty(prefix = "ateng.boot.excel.sample.custom-storage", name = "enabled", havingValue = "true")
public class CustomOssExportFileStorage implements ExportFileStorage {

    private static final Logger log = LoggerFactory.getLogger(CustomOssExportFileStorage.class);

    /**
     * 自定义 OSS 云分发下载前缀
     */
    public static final String OSS_URL_PREFIX = "https://oss.example.com/excel-exports/";

    private final LocalFileSystemStorage delegate;

    public CustomOssExportFileStorage(ExcelProperties properties) {
        this.delegate = new LocalFileSystemStorage(properties);
    }

    @Override
    public String store(String taskId, File tempFile, String fileName) throws IOException {
        String localKey = delegate.store(taskId, tempFile, fileName);
        log.info("[CustomOssExportFileStorage] 模拟上传文件至企业级 OSS: taskId={}, fileName={}, localKey={}", taskId, fileName, localKey);
        return "oss://" + taskId + "/" + fileName;
    }

    @Override
    public InputStream getInputStream(String taskId, String storageKey) throws IOException {
        return delegate.getInputStream(taskId, storageKey);
    }

    @Override
    public String getDownloadUrl(String taskId, String storageKey) {
        return OSS_URL_PREFIX + taskId + ".xlsx";
    }

    @Override
    public void delete(String taskId, String storageKey) {
        delegate.delete(taskId, storageKey);
        log.info("[CustomOssExportFileStorage] 模拟清理企业级 OSS 资源: taskId={}, storageKey={}", taskId, storageKey);
    }
}
