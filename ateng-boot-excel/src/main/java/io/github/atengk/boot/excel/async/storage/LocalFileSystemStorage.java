package io.github.atengk.boot.excel.async.storage;

import io.github.atengk.boot.excel.properties.ExcelProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;

/**
 * 基于本地文件系统的异步导出存储默认实现
 *
 * @author Ateng
 * @since 2026-10-20
 */
public class LocalFileSystemStorage implements ExportFileStorage {

    private static final Logger log = LoggerFactory.getLogger(LocalFileSystemStorage.class);

    private final Path basePath;

    public LocalFileSystemStorage() {
        this(new ExcelProperties());
    }

    public LocalFileSystemStorage(ExcelProperties properties) {
        String dir = (properties != null && properties.getAsyncExport() != null)
                ? properties.getAsyncExport().getTempDir()
                : null;

        if (dir == null || dir.isBlank()) {
            this.basePath = Paths.get(System.getProperty("java.io.tmpdir"), "ateng-excel-async");
        } else {
            this.basePath = Paths.get(dir);
        }

        try {
            Files.createDirectories(this.basePath);
        } catch (IOException e) {
            log.warn("创建异步导出存储基础目录失败: {}", e.getMessage());
        }
    }

    public Path getBasePath() {
        return basePath;
    }

    @Override
    public String store(String taskId, File tempFile, String fileName) throws IOException {
        Path targetPath = basePath.resolve(taskId + ".xlsx");
        if (!tempFile.toPath().equals(targetPath)) {
            Files.move(tempFile.toPath(), targetPath, StandardCopyOption.REPLACE_EXISTING);
        }
        return targetPath.toAbsolutePath().toString();
    }

    @Override
    public InputStream getInputStream(String taskId, String storageKey) throws IOException {
        Path path = resolvePath(taskId, storageKey);
        if (Files.exists(path)) {
            return Files.newInputStream(path);
        }
        return null;
    }

    @Override
    public String getDownloadUrl(String taskId, String storageKey) {
        return "/api/excel/async-download?taskId=" + taskId;
    }

    @Override
    public void delete(String taskId, String storageKey) {
        Path path = resolvePath(taskId, storageKey);
        try {
            Files.deleteIfExists(path);
        } catch (IOException e) {
            log.warn("删除本地异步导出文件失败 [{}]: {}", taskId, e.getMessage());
        }
    }

    private Path resolvePath(String taskId, String storageKey) {
        if (storageKey != null && !storageKey.isBlank()) {
            Path p = Paths.get(storageKey);
            if (p.isAbsolute()) {
                return p;
            }
            return basePath.resolve(storageKey);
        }
        return basePath.resolve(taskId + ".xlsx");
    }
}
