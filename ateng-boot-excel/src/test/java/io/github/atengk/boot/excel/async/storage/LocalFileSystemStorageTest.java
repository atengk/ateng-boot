package io.github.atengk.boot.excel.async.storage;

import io.github.atengk.boot.excel.properties.ExcelAsyncExportProperties;
import io.github.atengk.boot.excel.properties.ExcelProperties;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 本地文件存储 SPI 实现单元测试
 *
 * @author Ateng
 * @since 2026-10-20
 */
@DisplayName("本地异步导出文件存储测试")
class LocalFileSystemStorageTest {

    @TempDir
    Path tempDir;

    @Test
    @DisplayName("测试本地文件存储、读取与删除完整生命周期")
    void testLocalFileStorageLifecycle() throws Exception {
        ExcelProperties properties = new ExcelProperties();
        ExcelAsyncExportProperties asyncProps = properties.getAsyncExport();
        asyncProps.setTempDir(tempDir.toString());

        LocalFileSystemStorage storage = new LocalFileSystemStorage(properties);
        assertThat(storage.getBasePath()).isEqualTo(tempDir);

        String taskId = "test-task-1001";
        File tempFile = tempDir.resolve("source.tmp").toFile();
        Files.writeString(tempFile.toPath(), "Excel Test Data Content", StandardCharsets.UTF_8);

        // 1. 存储文件
        String storageKey = storage.store(taskId, tempFile, "测试报表.xlsx");
        assertThat(storageKey).isNotBlank();

        // 2. 读取文件
        try (InputStream in = storage.getInputStream(taskId, storageKey)) {
            assertThat(in).isNotNull();
            String content = new String(in.readAllBytes(), StandardCharsets.UTF_8);
            assertThat(content).isEqualTo("Excel Test Data Content");
        }

        // 3. 获取下载端点
        String downloadUrl = storage.getDownloadUrl(taskId, storageKey);
        assertThat(downloadUrl).contains("/api/excel/async-download?taskId=" + taskId);

        // 4. 删除文件
        storage.delete(taskId, storageKey);
        try (InputStream in = storage.getInputStream(taskId, storageKey)) {
            assertThat(in).isNull();
        }
    }
}
