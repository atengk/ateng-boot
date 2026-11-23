package io.github.atengk.boot.excel.async.storage;

import io.github.atengk.boot.excel.exception.ExcelValidationException;
import io.github.atengk.boot.excel.properties.ExcelProperties;
import io.github.atengk.boot.excel.util.ExcelSecurityUtil;
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
    private final String downloadUrlPrefix;

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

        String prefix = (properties != null && properties.getAsyncExport() != null)
                ? properties.getAsyncExport().getDownloadUrlPrefix()
                : null;
        this.downloadUrlPrefix = (prefix != null && !prefix.isBlank())
                ? prefix
                : "/api/excel/async-download?taskId=";

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
        Path normalizedBase = this.basePath.toAbsolutePath().normalize();
        Path targetPath = resolveSafeTaskTarget(normalizedBase, taskId);
        Path parent = targetPath.getParent();
        if (parent != null) {
            Files.createDirectories(parent);
        }
        if (!tempFile.toPath().toAbsolutePath().normalize().equals(targetPath)) {
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
        return downloadUrlPrefix + taskId;
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
        Path normalizedBase = this.basePath.toAbsolutePath().normalize();
        if (storageKey != null && !storageKey.isBlank()) {
            return ExcelSecurityUtil.resolveContainedPath(normalizedBase, storageKey);
        }
        return resolveSafeTaskTarget(normalizedBase, taskId);
    }

    private static Path resolveSafeTaskTarget(Path normalizedBase, String taskId) {
        if (taskId == null || taskId.isBlank()) {
            throw new ExcelValidationException("导出任务标识不能为空");
        }
        return ExcelSecurityUtil.resolveContainedPath(normalizedBase, taskId + ".xlsx");
    }
}
