package io.github.atengk.boot.ftp.template;

import io.github.atengk.boot.ftp.enums.FtpFileType;
import io.github.atengk.boot.ftp.enums.FtpMode;
import io.github.atengk.boot.ftp.exception.FtpException;
import io.github.atengk.boot.ftp.exception.FtpResultCode;
import io.github.atengk.boot.ftp.model.FtpFileInfo;
import io.github.atengk.boot.ftp.pool.FtpClientPooledObjectFactory;
import io.github.atengk.boot.ftp.properties.FtpProperties;
import org.apache.commons.net.ftp.FTPClient;
import org.apache.commons.pool2.impl.GenericObjectPool;
import org.apache.commons.pool2.impl.GenericObjectPoolConfig;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockftpserver.fake.FakeFtpServer;
import org.mockftpserver.fake.UserAccount;
import org.mockftpserver.fake.filesystem.DirectoryEntry;
import org.mockftpserver.fake.filesystem.FileSystem;
import org.mockftpserver.fake.filesystem.UnixFakeFileSystem;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.util.unit.DataSize;

import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * FtpTemplate 核心对称操作门面与流安全生命周期单元测试
 *
 * @author Ateng
 * @since 2026-10-22
 */
@DisplayName("FtpTemplate 核心对称操作门面与流安全生命周期单元测试")
class FtpTemplateTest {

    private static final String USERNAME = "ftpUser";
    private static final String PASSWORD = "ftpPassword";

    private FakeFtpServer fakeFtpServer;
    private GenericObjectPool<FTPClient> pool;
    private FtpProperties properties;
    private FtpTemplate ftpTemplate;

    @BeforeEach
    void setUp() {
        fakeFtpServer = new FakeFtpServer();
        fakeFtpServer.setServerControlPort(0);
        fakeFtpServer.addUserAccount(new UserAccount(USERNAME, PASSWORD, "/"));

        FileSystem fileSystem = new UnixFakeFileSystem();
        fileSystem.add(new DirectoryEntry("/"));
        fileSystem.add(new DirectoryEntry("/test"));
        fileSystem.add(new DirectoryEntry("/测试目录"));
        fakeFtpServer.setFileSystem(fileSystem);

        fakeFtpServer.start();

        properties = new FtpProperties();
        properties.setHost("127.0.0.1");
        properties.setPort(fakeFtpServer.getServerControlPort());
        properties.setUsername(USERNAME);
        properties.setPassword(PASSWORD);
        properties.setMode(FtpMode.PASSIVE);
        properties.setFileType(FtpFileType.BINARY);
        properties.setControlEncoding("UTF-8");
        properties.setConnectTimeout(Duration.ofSeconds(3));
        properties.setDataTimeout(Duration.ofSeconds(3));

        FtpClientPooledObjectFactory factory = new FtpClientPooledObjectFactory(properties);
        GenericObjectPoolConfig<FTPClient> poolConfig = new GenericObjectPoolConfig<>();
        poolConfig.setMaxTotal(4);
        poolConfig.setMaxIdle(2);
        poolConfig.setMinIdle(1);
        poolConfig.setTestOnBorrow(true);

        pool = new GenericObjectPool<>(factory, poolConfig);
        ftpTemplate = new FtpTemplate(pool, properties);
    }

    @AfterEach
    void tearDown() {
        if (pool != null) {
            pool.close();
        }
        if (fakeFtpServer != null) {
            fakeFtpServer.stop();
        }
    }

    @Test
    @DisplayName("测试输入流上传与受管 downloadStream 安全流消费")
    void testUploadAndDownloadStream() {
        String content = "Hello ateng-boot FTP!";
        byte[] bytes = content.getBytes(StandardCharsets.UTF_8);

        boolean uploaded = ftpTemplate.upload("/test/hello.txt", new ByteArrayInputStream(bytes));
        assertThat(uploaded).isTrue();

        AtomicReference<String> downloadedContent = new AtomicReference<>();
        ftpTemplate.downloadStream("/test/hello.txt", in -> {
            try {
                downloadedContent.set(new String(in.readAllBytes(), StandardCharsets.UTF_8));
            } catch (IOException e) {
                throw new RuntimeException(e);
            }
        });

        assertThat(downloadedContent.get()).isEqualTo(content);
    }

    @Test
    @DisplayName("测试本地文件上传与下载对称操作")
    void testUploadAndDownloadFile(@TempDir Path tempDir) throws IOException {
        Path sourcePath = tempDir.resolve("source.txt");
        String content = "Local file payload test";
        Files.writeString(sourcePath, content, StandardCharsets.UTF_8);

        boolean uploaded = ftpTemplate.upload("/test/local.txt", sourcePath.toFile());
        assertThat(uploaded).isTrue();

        Path destPath = tempDir.resolve("downloaded.txt");
        boolean downloaded = ftpTemplate.download("/test/local.txt", destPath.toFile());
        assertThat(downloaded).isTrue();
        assertThat(Files.readString(destPath, StandardCharsets.UTF_8)).isEqualTo(content);
    }

    @Test
    @DisplayName("测试字节数组上传与 downloadToBytes 下载")
    void testUploadAndDownloadBytes() {
        String content = "Byte array payload test";
        byte[] bytes = content.getBytes(StandardCharsets.UTF_8);

        boolean uploaded = ftpTemplate.upload("/test/bytes.txt", bytes);
        assertThat(uploaded).isTrue();

        byte[] downloadedBytes = ftpTemplate.downloadToBytes("/test/bytes.txt");
        assertThat(downloadedBytes).isEqualTo(bytes);
    }

    @Test
    @DisplayName("测试 Spring Resource 上传")
    void testUploadResource() {
        String content = "Spring resource payload test";
        ByteArrayResource resource = new ByteArrayResource(content.getBytes(StandardCharsets.UTF_8), "resource.txt");

        boolean uploaded = ftpTemplate.upload("/test/resource.txt", resource);
        assertThat(uploaded).isTrue();

        byte[] downloadedBytes = ftpTemplate.downloadToBytes("/test/resource.txt");
        assertThat(new String(downloadedBytes, StandardCharsets.UTF_8)).isEqualTo(content);
    }

    @Test
    @DisplayName("测试下载不存在的文件抛出 FILE_NOT_FOUND 异常")
    void testDownloadStreamFileNotFound() {
        assertThatThrownBy(() -> ftpTemplate.downloadStream("/non_existent_file.txt", in -> {}))
                .isInstanceOf(FtpException.class)
                .extracting(e -> ((FtpException) e).getCode())
                .isEqualTo(FtpResultCode.FILE_NOT_FOUND.getCode());
    }

    @Test
    @DisplayName("测试 downloadToBytes 超出内存安全阈值熔断防 OOM")
    void testDownloadToBytesExceedsLimit() {
        properties.setMaxDownloadBytes(DataSize.ofBytes(10));

        byte[] payload = "This is a payload definitely longer than ten bytes!".getBytes(StandardCharsets.UTF_8);
        ftpTemplate.upload("/test/overflow.txt", payload);

        assertThatThrownBy(() -> ftpTemplate.downloadToBytes("/test/overflow.txt"))
                .isInstanceOf(FtpException.class)
                .hasMessageContaining("超过内存安全阈值");
    }

    @Test
    @DisplayName("测试中文路径 RFC 959 协议线缆转码读写一致性")
    void testChinesePathWireTranscoding() {
        String content = "中文路径测试内容：2026年账单数据";
        byte[] bytes = content.getBytes(StandardCharsets.UTF_8);

        // 上传到中文目录与中文文件名
        String chinesePath = "/测试目录/2026年10月账单.csv";
        boolean uploaded = ftpTemplate.upload(chinesePath, bytes);
        assertThat(uploaded).isTrue();

        // 验证下载
        byte[] downloaded = ftpTemplate.downloadToBytes(chinesePath);
        assertThat(new String(downloaded, StandardCharsets.UTF_8)).isEqualTo(content);
    }

    @Test
    @DisplayName("测试目录列表获取与过滤条件")
    void testListFilesWithFilter() {
        ftpTemplate.upload("/test/order1.csv", "order1".getBytes(StandardCharsets.UTF_8));
        ftpTemplate.upload("/test/order2.csv", "order2".getBytes(StandardCharsets.UTF_8));
        ftpTemplate.upload("/test/ignore.txt", "ignore".getBytes(StandardCharsets.UTF_8));

        List<FtpFileInfo> allFiles = ftpTemplate.listFiles("/test");
        assertThat(allFiles).hasSize(3);

        List<FtpFileInfo> csvFiles = ftpTemplate.listFiles("/test", file -> file.getName().endsWith(".csv"));
        assertThat(csvFiles).hasSize(2)
                .allMatch(file -> file.getName().endsWith(".csv"));
    }

    @Test
    @DisplayName("测试协议线缆转码辅助方法对称性")
    void testWirePathHelpers() {
        // 1. 默认 UTF-8 编码下直接透传
        String originalChinese = "/测试目录/账单.csv";
        assertThat(ftpTemplate.toWirePath(originalChinese)).isEqualTo(originalChinese);
        assertThat(ftpTemplate.fromWirePath(originalChinese)).isEqualTo(originalChinese);

        // 2. 在 ISO-8859-1 线缆模式下执行 RFC 959 字节转码
        properties.setControlEncoding("ISO-8859-1");
        String wirePath = ftpTemplate.toWirePath(originalChinese);
        assertThat(wirePath).isNotEqualTo(originalChinese);
        assertThat(ftpTemplate.fromWirePath(wirePath)).isEqualTo(originalChinese);

        // 恢复默认配置
        properties.setControlEncoding("UTF-8");
    }
}
