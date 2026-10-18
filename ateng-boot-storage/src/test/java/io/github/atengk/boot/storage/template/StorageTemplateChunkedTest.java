package io.github.atengk.boot.storage.template;

import io.github.atengk.boot.storage.driver.LocalDriver;
import io.github.atengk.boot.storage.event.StorageUploadedEvent;
import io.github.atengk.boot.storage.exception.StorageException;
import io.github.atengk.boot.storage.exception.StorageResultCode;
import io.github.atengk.boot.storage.model.StorageObject;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.context.ApplicationEventPublisher;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * StorageTemplate 大文件分片上传、连续性校验、状态机与碎片清理集成测试
 *
 * @author Ateng
 * @since 2026-10-18
 */
class StorageTemplateChunkedTest {

    @TempDir
    Path tempDir;

    private LocalDriver driver;
    private StorageTemplate template;
    private List<StorageUploadedEvent> publishedEvents;

    @BeforeEach
    void setUp() {
        driver = new LocalDriver(tempDir.toString());
        template = new StorageTemplate(driver);
        publishedEvents = new ArrayList<>();
        ApplicationEventPublisher mockPublisher = event -> {
            if (event instanceof StorageUploadedEvent uploadedEvent) {
                publishedEvents.add(uploadedEvent);
            }
        };
        template.setApplicationEventPublisher(mockPublisher);
    }

    @Test
    @DisplayName("测试大文件分片初始化、正序上传、合并与碎片即时清理")
    void testChunkedUploadCompleteAndClean() throws Exception {
        String targetPath = "large/dataset.bin";

        // 1. 初始化分片会话
        String uploadId = template.initiateChunkedUpload(targetPath);
        assertThat(uploadId).isNotBlank();

        Path chunkDir = tempDir.resolve(".chunks").resolve(uploadId);
        assertThat(Files.exists(chunkDir)).isTrue();

        // 2. 准备 3 个分片数据
        byte[] chunk1 = "Chunk-1-Data-AAAAA".getBytes(StandardCharsets.UTF_8);
        byte[] chunk2 = "Chunk-2-Data-BBBBBBBBBB".getBytes(StandardCharsets.UTF_8);
        byte[] chunk3 = "Chunk-3-Data-CCCCCCCCCCCCCCC".getBytes(StandardCharsets.UTF_8);

        byte[] expectedMerged = new byte[chunk1.length + chunk2.length + chunk3.length];
        System.arraycopy(chunk1, 0, expectedMerged, 0, chunk1.length);
        System.arraycopy(chunk2, 0, expectedMerged, chunk1.length, chunk2.length);
        System.arraycopy(chunk3, 0, expectedMerged, chunk1.length + chunk2.length, chunk3.length);
        String expectedMd5 = md5Hex(expectedMerged);

        // 3. 上传分片 (支持多重载)
        template.uploadChunk(uploadId, 1, new ByteArrayInputStream(chunk1), chunk1.length);
        template.uploadChunk(uploadId, 2, new ByteArrayInputStream(chunk2));
        template.uploadChunk(uploadId, 3, chunk3);

        // 4. 正序合并分片
        StorageObject mergedObj = template.completeChunkedUpload(uploadId, targetPath, 3);
        assertThat(mergedObj).isNotNull();
        assertThat(mergedObj.getPath()).isEqualTo(targetPath);
        assertThat(mergedObj.getSize()).isEqualTo((long) expectedMerged.length);
        assertThat(mergedObj.getEtag()).isEqualTo(expectedMd5);

        // 5. 验证目标文件已写入且内容完全一致
        try (InputStream in = template.download(targetPath)) {
            byte[] downloaded = in.readAllBytes();
            assertThat(downloaded).isEqualTo(expectedMerged);
            assertThat(md5Hex(downloaded)).isEqualTo(expectedMd5);
        }

        // 6. 验证临时分片目录已彻底自动清理
        assertThat(Files.exists(chunkDir)).isFalse();

        // 7. 验证成功广播 StorageUploadedEvent 事件
        assertThat(publishedEvents).hasSize(1);
        StorageUploadedEvent event = publishedEvents.get(0);
        assertThat(event.getPath()).isEqualTo(targetPath);
        assertThat(event.getSize()).isEqualTo((long) expectedMerged.length);
    }

    @Test
    @DisplayName("测试分片缺失阻断合并并抛出 STORAGE_CHUNK_MISSING")
    void testChunkedUploadMissingChunk() {
        String targetPath = "videos/movie.mp4";
        String uploadId = template.initiateChunkedUpload(targetPath);

        // 上传第 1 片和第 3 片，故意跳过第 2 片
        template.uploadChunk(uploadId, 1, "Part1".getBytes(StandardCharsets.UTF_8));
        template.uploadChunk(uploadId, 3, "Part3".getBytes(StandardCharsets.UTF_8));

        assertThatThrownBy(() -> template.completeChunkedUpload(uploadId, targetPath, 3))
                .isInstanceOf(StorageException.class)
                .satisfies(e -> {
                    StorageException se = (StorageException) e;
                    assertThat(se.getResultCode()).isEqualTo(StorageResultCode.STORAGE_CHUNK_MISSING);
                    assertThat(se.getMessage()).contains("缺失第 2 个分片");
                });

        // 验证碎片未被误删
        Path chunkDir = tempDir.resolve(".chunks").resolve(uploadId);
        assertThat(Files.exists(chunkDir)).isTrue();
    }

    @Test
    @DisplayName("测试非法分片索引与总片数校验触发 STORAGE_CHUNK_INVALID")
    void testInvalidChunkParameters() {
        String uploadId = template.initiateChunkedUpload("test.bin");

        // 1. 0 索引非法
        assertThatThrownBy(() -> template.uploadChunk(uploadId, 0, "Data".getBytes(StandardCharsets.UTF_8)))
                .isInstanceOf(StorageException.class)
                .satisfies(e -> {
                    StorageException se = (StorageException) e;
                    assertThat(se.getResultCode()).isEqualTo(StorageResultCode.STORAGE_CHUNK_INVALID);
                });

        // 2. 负数索引非法
        assertThatThrownBy(() -> template.uploadChunk(uploadId, -1, "Data".getBytes(StandardCharsets.UTF_8)))
                .isInstanceOf(StorageException.class)
                .satisfies(e -> {
                    StorageException se = (StorageException) e;
                    assertThat(se.getResultCode()).isEqualTo(StorageResultCode.STORAGE_CHUNK_INVALID);
                });

        // 3. 总片数必须 >= 1
        assertThatThrownBy(() -> template.completeChunkedUpload(uploadId, "test.bin", 0))
                .isInstanceOf(StorageException.class)
                .satisfies(e -> {
                    StorageException se = (StorageException) e;
                    assertThat(se.getResultCode()).isEqualTo(StorageResultCode.STORAGE_CHUNK_INVALID);
                });
    }

    @Test
    @DisplayName("测试主动中止分片上传并清理临时碎片")
    void testAbortChunkedUpload() {
        String uploadId = template.initiateChunkedUpload("archive/data.zip");
        Path chunkDir = tempDir.resolve(".chunks").resolve(uploadId);
        assertThat(Files.exists(chunkDir)).isTrue();

        template.uploadChunk(uploadId, 1, "Part1".getBytes(StandardCharsets.UTF_8));
        template.uploadChunk(uploadId, 2, "Part2".getBytes(StandardCharsets.UTF_8));
        assertThat(Files.exists(chunkDir.resolve("chunk_1"))).isTrue();

        // 中止上传
        template.abortChunkedUpload(uploadId);

        // 验证碎片目录被彻底清除
        assertThat(Files.exists(chunkDir)).isFalse();
    }

    private String md5Hex(byte[] data) throws Exception {
        MessageDigest md = MessageDigest.getInstance("MD5");
        return HexFormat.of().formatHex(md.digest(data));
    }
}
