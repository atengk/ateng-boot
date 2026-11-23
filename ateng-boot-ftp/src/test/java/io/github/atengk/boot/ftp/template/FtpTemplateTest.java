package io.github.atengk.boot.ftp.template;

import io.github.atengk.boot.ftp.enums.FtpFileType;
import io.github.atengk.boot.ftp.enums.FtpMode;
import io.github.atengk.boot.ftp.exception.FtpException;
import io.github.atengk.boot.ftp.exception.FtpResultCode;
import io.github.atengk.boot.ftp.listener.FtpProgressListener;
import io.github.atengk.boot.ftp.model.BatchTransferResult;
import io.github.atengk.boot.ftp.model.FtpFileInfo;
import io.github.atengk.boot.ftp.model.TransferFailureItem;
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
import org.mockftpserver.fake.filesystem.FileEntry;
import org.mockftpserver.fake.filesystem.FileSystem;
import org.mockftpserver.fake.filesystem.Permissions;
import org.mockftpserver.fake.filesystem.UnixFakeFileSystem;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.util.unit.DataSize;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.NoSuchElementException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
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

    @Test
    @DisplayName("测试原子上传成功后临时文件无残留且目标文件存在")
    void testAtomicUploadSuccessAndTempClean() {
        properties.setAtomicUpload(true);
        String targetPath = "/test/atomic_ok.txt";
        byte[] payload = "Atomic payload content".getBytes(StandardCharsets.UTF_8);

        boolean uploaded = ftpTemplate.upload(targetPath, payload);
        assertThat(uploaded).isTrue();

        // 验证目标文件已存在且临时文件 .tmp 不存在
        assertThat(ftpTemplate.exists(targetPath)).isTrue();
        assertThat(ftpTemplate.exists(targetPath + ".tmp")).isFalse();

        byte[] downloaded = ftpTemplate.downloadToBytes(targetPath);
        assertThat(downloaded).isEqualTo(payload);
    }

    @Test
    @DisplayName("测试原子上传异常中断时自动物理清理 .tmp 临时文件")
    void testAtomicUploadCompensationOnStreamError() {
        properties.setAtomicUpload(true);
        String targetPath = "/test/broken.txt";

        // 模拟读取中途发生 IO 异常断流
        InputStream brokenIn = new InputStream() {
            private int count = 0;

            @Override
            public int read() throws IOException {
                if (++count > 5) {
                    throw new IOException("Simulated network stream break during upload");
                }
                return 'X';
            }
        };

        assertThatThrownBy(() -> ftpTemplate.upload(targetPath, brokenIn))
                .isInstanceOf(FtpException.class)
                .extracting(e -> ((FtpException) e).getCode())
                .isEqualTo(FtpResultCode.UPLOAD_FAILED.getCode());

        // 验证补偿清理逻辑生效：临时文件与目标文件均不存在
        assertThat(ftpTemplate.exists(targetPath)).isFalse();
        assertThat(ftpTemplate.exists(targetPath + ".tmp")).isFalse();
    }

    @Test
    @DisplayName("测试路径防穿越安全清洗拦截各种跨目录序列")
    void testPathTraversalGuardOnOperations() {
        byte[] payload = "attack payload".getBytes(StandardCharsets.UTF_8);

        // 1. 上传路径穿越
        assertThatThrownBy(() -> ftpTemplate.upload("/test/../secret.txt", payload))
                .isInstanceOf(FtpException.class)
                .extracting(e -> ((FtpException) e).getCode())
                .isEqualTo(FtpResultCode.INVALID_PATH.getCode());

        assertThatThrownBy(() -> ftpTemplate.upload("..\\evil.txt", payload))
                .isInstanceOf(FtpException.class)
                .extracting(e -> ((FtpException) e).getCode())
                .isEqualTo(FtpResultCode.INVALID_PATH.getCode());

        // 2. 下载路径穿越
        assertThatThrownBy(() -> ftpTemplate.download("../etc/passwd", new ByteArrayOutputStream()))
                .isInstanceOf(FtpException.class)
                .extracting(e -> ((FtpException) e).getCode())
                .isEqualTo(FtpResultCode.INVALID_PATH.getCode());

        // 3. exists 路径穿越
        assertThatThrownBy(() -> ftpTemplate.exists("/test/.."))
                .isInstanceOf(FtpException.class)
                .extracting(e -> ((FtpException) e).getCode())
                .isEqualTo(FtpResultCode.INVALID_PATH.getCode());

        // 4. deleteFile 路径穿越
        assertThatThrownBy(() -> ftpTemplate.deleteFile("../escape.txt"))
                .isInstanceOf(FtpException.class)
                .extracting(e -> ((FtpException) e).getCode())
                .isEqualTo(FtpResultCode.INVALID_PATH.getCode());

        // 5. rename 路径穿越
        assertThatThrownBy(() -> ftpTemplate.rename("/test/normal.txt", "../escape.txt"))
                .isInstanceOf(FtpException.class)
                .extracting(e -> ((FtpException) e).getCode())
                .isEqualTo(FtpResultCode.INVALID_PATH.getCode());
    }

    @Test
    @DisplayName("测试上传深层目录时自动递归创建父目录")
    void testAutoCreateDirectoriesOnDeepUpload() {
        properties.setAutoCreateDirectories(true);
        String deepFile = "/test/auto_dir/2026/10/22/report.txt";
        byte[] content = "deep path upload report".getBytes(StandardCharsets.UTF_8);

        boolean uploaded = ftpTemplate.upload(deepFile, content);
        assertThat(uploaded).isTrue();

        assertThat(ftpTemplate.exists(deepFile)).isTrue();
        assertThat(ftpTemplate.exists("/test/auto_dir/2026/10/22")).isTrue();

        byte[] downloaded = ftpTemplate.downloadToBytes(deepFile);
        assertThat(new String(downloaded, StandardCharsets.UTF_8)).isEqualTo("deep path upload report");
    }

    @Test
    @DisplayName("测试高级文件系统操作 API 完整生命周期")
    void testAdvancedFileSystemOperations() {
        // 1. makeDirectories 递归级联建目录
        boolean dirsCreated = ftpTemplate.makeDirectories("/test/nested/l1/l2");
        assertThat(dirsCreated).isTrue();
        assertThat(ftpTemplate.exists("/test/nested/l1/l2")).isTrue();

        // 2. exists 与 getFileInfo
        ftpTemplate.upload("/test/nested/l1/l2/info.txt", "info content".getBytes(StandardCharsets.UTF_8));
        assertThat(ftpTemplate.exists("/test/nested/l1/l2/info.txt")).isTrue();
        assertThat(ftpTemplate.exists("/test/nested/not_found.txt")).isFalse();

        FtpFileInfo info = ftpTemplate.getFileInfo("/test/nested/l1/l2/info.txt");
        assertThat(info).isNotNull();
        assertThat(info.getName()).isEqualTo("info.txt");
        assertThat(info.isFile()).isTrue();
        assertThat(info.isDirectory()).isFalse();
        assertThat(info.getSize()).isGreaterThan(0);

        // 验证目录的 getFileInfo 正确识别为目录且名字为 l2，彻底防御误将首个子文件 info.txt 当作目录元数据
        FtpFileInfo dirInfo = ftpTemplate.getFileInfo("/test/nested/l1/l2");
        assertThat(dirInfo).isNotNull();
        assertThat(dirInfo.getName()).isEqualTo("l2");
        assertThat(dirInfo.isDirectory()).isTrue();
        assertThat(dirInfo.isFile()).isFalse();

        FtpFileInfo rootInfo = ftpTemplate.getFileInfo("/");
        assertThat(rootInfo).isNotNull();
        assertThat(rootInfo.isDirectory()).isTrue();

        FtpFileInfo notFoundInfo = ftpTemplate.getFileInfo("/test/not_exist.txt");
        assertThat(notFoundInfo).isNull();

        // 3. listDirectories
        List<String> directories = ftpTemplate.listDirectories("/test/nested");
        assertThat(directories).isNotEmpty();

        // 4. rename
        boolean renamed = ftpTemplate.rename("/test/nested/l1/l2/info.txt", "/test/nested/l1/l2/info_renamed.txt");
        assertThat(renamed).isTrue();
        assertThat(ftpTemplate.exists("/test/nested/l1/l2/info.txt")).isFalse();
        assertThat(ftpTemplate.exists("/test/nested/l1/l2/info_renamed.txt")).isTrue();

        // 5. move
        boolean moved = ftpTemplate.move("/test/nested/l1/l2/info_renamed.txt", "/test/moved_target");
        assertThat(moved).isTrue();
        assertThat(ftpTemplate.exists("/test/nested/l1/l2/info_renamed.txt")).isFalse();
        assertThat(ftpTemplate.exists("/test/moved_target/info_renamed.txt")).isTrue();

        // 6. deleteFile
        boolean fileDeleted = ftpTemplate.deleteFile("/test/moved_target/info_renamed.txt");
        assertThat(fileDeleted).isTrue();
        assertThat(ftpTemplate.exists("/test/moved_target/info_renamed.txt")).isFalse();

        // 7. deleteDirectory 递归清空删除非空目录
        ftpTemplate.upload("/test/dir_to_del/sub/a.txt", "a".getBytes(StandardCharsets.UTF_8));
        ftpTemplate.upload("/test/dir_to_del/sub/b.txt", "b".getBytes(StandardCharsets.UTF_8));
        assertThat(ftpTemplate.exists("/test/dir_to_del/sub/a.txt")).isTrue();

        boolean dirDeleted = ftpTemplate.deleteDirectory("/test/dir_to_del", true);
        assertThat(dirDeleted).isTrue();
        assertThat(ftpTemplate.exists("/test/dir_to_del")).isFalse();
    }

    @Test
    @DisplayName("测试原子上传失败补偿在 maxTotal=1 单连接池场景下绝无死锁")
    void testAtomicUploadDeadlockFreeWithSingleConnectionPool() {
        GenericObjectPoolConfig<FTPClient> singleConfig = new GenericObjectPoolConfig<>();
        singleConfig.setMaxTotal(1);
        singleConfig.setMaxIdle(1);
        singleConfig.setMinIdle(1);
        FtpClientPooledObjectFactory singleFactory = new FtpClientPooledObjectFactory(properties);
        GenericObjectPool<FTPClient> singlePool = new GenericObjectPool<>(singleFactory, singleConfig);
        FtpTemplate singleTemplate = new FtpTemplate(singlePool, properties);

        try {
            // 构造一个在读取中途抛出 IOException 的损坏流模拟上传异常中断
            InputStream brokenStream = new InputStream() {
                private int readCount = 0;

                @Override
                public int read() throws IOException {
                    if (++readCount > 5) {
                        throw new IOException("Simulated network stream break");
                    }
                    return 'A';
                }
            };

            // 触发上传，中途写入中断，触发异常补偿逻辑执行 deleteFile(.tmp)
            assertThatThrownBy(() -> singleTemplate.upload("/test/deadlock_test.txt", brokenStream))
                    .isInstanceOf(FtpException.class);

            // 随后在相同连接池中再次执行普通上传，断言能够正常借还连接，证明未发生连接池自死锁且临时脏文件已被清理
            boolean ok = singleTemplate.upload("/test/deadlock_recovered.txt", "recovered".getBytes(StandardCharsets.UTF_8));
            assertThat(ok).isTrue();
            assertThat(singleTemplate.exists("/test/deadlock_recovered.txt")).isTrue();
            assertThat(singleTemplate.exists("/test/deadlock_test.txt.tmp")).isFalse();
        } finally {
            singlePool.close();
        }
    }

    @Test
    @DisplayName("测试 downloadStream 消费者抛出业务异常时协议正常闭合且后续连接池可用")
    void testDownloadStreamConsumerThrowsException() {
        String testPath = "/test/stream_err_test.txt";
        ftpTemplate.upload(testPath, "test payload".getBytes(StandardCharsets.UTF_8));

        // 回调中故意抛出运行时异常
        assertThatThrownBy(() -> ftpTemplate.downloadStream(testPath, in -> {
            throw new RuntimeException("Simulated business callback error");
        })).isInstanceOf(RuntimeException.class)
                .hasMessageContaining("Simulated business callback error");

        // 断言后续 FTP 操作仍能正常执行，连接池未受污染
        byte[] downloaded = ftpTemplate.downloadToBytes(testPath);
        assertThat(new String(downloaded, StandardCharsets.UTF_8)).isEqualTo("test payload");
    }

    @Test
    @DisplayName("测试物理连通性自验健康探针 (健康节点与不可达节点)")
    void testConnectionProbes() {
        // 1. 测试正常服务节点
        assertThat(ftpTemplate.testConnection()).isTrue();
        ftpTemplate.testConnectionOrThrow();

        // 2. 测试不可达/错误端口服务节点
        FtpProperties brokenProps = new FtpProperties();
        brokenProps.setHost("127.0.0.1");
        brokenProps.setPort(59999);
        brokenProps.setUsername("wrong_user");
        brokenProps.setPassword("wrong_pass");
        brokenProps.setConnectTimeout(Duration.ofMillis(300));
        brokenProps.setDataTimeout(Duration.ofMillis(300));

        FtpClientPooledObjectFactory brokenFactory = new FtpClientPooledObjectFactory(brokenProps);
        GenericObjectPool<FTPClient> brokenPool = new GenericObjectPool<>(brokenFactory);
        FtpTemplate brokenTemplate = new FtpTemplate(brokenPool, brokenProps);

        try {
            assertThat(brokenTemplate.testConnection()).isFalse();
            assertThatThrownBy(brokenTemplate::testConnectionOrThrow)
                    .isInstanceOf(FtpException.class)
                    .satisfies(e -> assertThat(((FtpException) e).getCode()).isEqualTo(FtpResultCode.CONNECT_FAILED.getCode()));
        } finally {
            brokenPool.close();
        }
    }

    @Test
    @DisplayName("测试增量追加写 (append)")
    void testAppend() {
        String testPath = "/test/append_test.txt";
        ftpTemplate.writeString(testPath, "Initial");

        boolean appendedText = ftpTemplate.append(testPath, " + Content");
        assertThat(appendedText).isTrue();

        boolean appendedBytes = ftpTemplate.append(testPath, " + Bytes".getBytes(StandardCharsets.UTF_8));
        assertThat(appendedBytes).isTrue();

        boolean appendedStream = ftpTemplate.append(testPath, new ByteArrayInputStream(" + Stream".getBytes(StandardCharsets.UTF_8)));
        assertThat(appendedStream).isTrue();

        String finalContent = ftpTemplate.readString(testPath);
        assertThat(finalContent).isEqualTo("Initial + Content + Bytes + Stream");
    }

    @Test
    @DisplayName("测试受管单文件管道原子复制 (copy) 与边界校验")
    void testCopy() {
        String sourcePath = "/test/source_copy.txt";
        String destPath = "/test/dest_copy.txt";
        String content = "Copy Payload 2026";
        ftpTemplate.writeString(sourcePath, content);

        // 1. 成功复制
        boolean copied = ftpTemplate.copy(sourcePath, destPath);
        assertThat(copied).isTrue();
        assertThat(ftpTemplate.exists(sourcePath)).isTrue();
        assertThat(ftpTemplate.exists(destPath)).isTrue();
        assertThat(ftpTemplate.readString(destPath)).isEqualTo(content);

        // 2. 源文件不存在异常
        assertThatThrownBy(() -> ftpTemplate.copy("/test/not_exist_source.txt", "/test/out.txt"))
                .isInstanceOf(FtpException.class)
                .satisfies(e -> assertThat(((FtpException) e).getCode()).isEqualTo(FtpResultCode.FILE_NOT_FOUND.getCode()));

        // 3. 源文件为目录异常
        assertThatThrownBy(() -> ftpTemplate.copy("/test", "/test_dest"))
                .isInstanceOf(FtpException.class)
                .satisfies(e -> assertThat(((FtpException) e).getCode()).isEqualTo(FtpResultCode.INVALID_PATH.getCode()));

        // 4. 源路径与目标路径相同异常
        assertThatThrownBy(() -> ftpTemplate.copy(sourcePath, sourcePath))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("测试大文件带 FtpProgressListener 进度监听的上传与下载对称操作")
    void testUploadAndDownloadWithProgressListener(@TempDir Path tempDir) throws IOException {
        Path localFile = tempDir.resolve("large_payload.bin");
        byte[] payload = new byte[128 * 1024]; // 128KB payload
        for (int i = 0; i < payload.length; i++) {
            payload[i] = (byte) (i % 127);
        }
        Files.write(localFile, payload);

        // 1. 测试文件上传并挂载进度监听器
        AtomicReference<Double> lastUploadPercentage = new AtomicReference<>(0.0);
        AtomicReference<Long> lastUploadBytes = new AtomicReference<>(0L);
        boolean uploaded = ftpTemplate.upload("/test/large_payload.bin", localFile.toFile(),
                (bytesTransferred, totalBytes, percentage) -> {
                    lastUploadBytes.set(bytesTransferred);
                    lastUploadPercentage.set(percentage);
                });

        assertThat(uploaded).isTrue();
        assertThat(lastUploadBytes.get()).isEqualTo((long) payload.length);
        assertThat(lastUploadPercentage.get()).isEqualTo(100.0);

        // 2. 测试文件下载并挂载进度监听器
        Path downloadedFile = tempDir.resolve("downloaded_payload.bin");
        AtomicReference<Double> lastDownloadPercentage = new AtomicReference<>(0.0);
        AtomicReference<Long> lastDownloadBytes = new AtomicReference<>(0L);
        boolean downloaded = ftpTemplate.download("/test/large_payload.bin", downloadedFile.toFile(),
                (bytesTransferred, totalBytes, percentage) -> {
                    lastDownloadBytes.set(bytesTransferred);
                    lastDownloadPercentage.set(percentage);
                });

        assertThat(downloaded).isTrue();
        assertThat(lastDownloadBytes.get()).isEqualTo((long) payload.length);
        assertThat(lastDownloadPercentage.get()).isEqualTo(100.0);
        assertThat(Files.readAllBytes(downloadedFile)).isEqualTo(payload);
    }

    @Test
    @DisplayName("测试批量全目录递归镜像上传与下载 (uploadDirectory / downloadDirectory)")
    void testUploadDirectoryAndDownloadDirectory(@TempDir Path tempDir) throws IOException {
        Path srcDir = tempDir.resolve("src_sync");
        Files.createDirectories(srcDir.resolve("sub/deep"));
        Files.writeString(srcDir.resolve("root.txt"), "Root Content");
        Files.writeString(srcDir.resolve("sub/sub.csv"), "col1,col2");
        Files.writeString(srcDir.resolve("sub/deep/deep.json"), "{\"key\": \"val\"}");

        // 1. 批量上传目录
        BatchTransferResult uploadResult = ftpTemplate.uploadDirectory(srcDir.toFile(), "/sync_remote");
        assertThat(uploadResult.isAllSuccess()).isTrue();
        assertThat(uploadResult.hasFailure()).isFalse();
        assertThat(uploadResult.getTotalFiles()).isEqualTo(3);
        assertThat(uploadResult.getSuccessCount()).isEqualTo(3);
        assertThat(uploadResult.getFailureCount()).isEqualTo(0);
        assertThat(uploadResult.getSkippedCount()).isEqualTo(0);
        assertThat(uploadResult.getTransferredBytes()).isPositive();
        assertThat(uploadResult.getDuration()).isNotNull();
        assertThat(uploadResult.getFailures()).isEmpty();

        // 2. 批量下载目录
        Path destDir = tempDir.resolve("dest_sync");
        BatchTransferResult downloadResult = ftpTemplate.downloadDirectory("/sync_remote", destDir.toFile());
        assertThat(downloadResult.isAllSuccess()).isTrue();
        assertThat(downloadResult.hasFailure()).isFalse();
        assertThat(downloadResult.getTotalFiles()).isEqualTo(3);
        assertThat(downloadResult.getSuccessCount()).isEqualTo(3);
        assertThat(downloadResult.getFailureCount()).isEqualTo(0);
        assertThat(downloadResult.getFailures()).isEmpty();

        // 3. 校验下载到本地的文件内容一致性
        assertThat(Files.readString(destDir.resolve("root.txt"))).isEqualTo("Root Content");
        assertThat(Files.readString(destDir.resolve("sub/sub.csv"))).isEqualTo("col1,col2");
        assertThat(Files.readString(destDir.resolve("sub/deep/deep.json"))).isEqualTo("{\"key\": \"val\"}");
    }

    @Test
    @DisplayName("测试批量上传时操作系统隐藏与系统元数据文件自动过滤 (desktop.ini / .DS_Store / Thumbs.db / .git / ._*)")
    void testUploadDirectoryHiddenAndSystemFilesFiltering(@TempDir Path tempDir) throws IOException {
        Path hiddenDir = tempDir.resolve("hidden_dir");
        Files.createDirectories(hiddenDir.resolve(".git"));
        Files.writeString(hiddenDir.resolve("desktop.ini"), "desktop_ini_content");
        Files.writeString(hiddenDir.resolve(".DS_Store"), "ds_store_content");
        Files.writeString(hiddenDir.resolve("Thumbs.db"), "thumbs_db_content");
        Files.writeString(hiddenDir.resolve("._resource_fork"), "mac_resource_fork");
        Files.writeString(hiddenDir.resolve(".git/HEAD"), "ref: refs/heads/main");
        Files.writeString(hiddenDir.resolve("legit.txt"), "Legit Content");

        BatchTransferResult result = ftpTemplate.uploadDirectory(hiddenDir.toFile(), "/hidden_remote");
        assertThat(result.isAllSuccess()).isTrue();
        assertThat(result.getSuccessCount()).isEqualTo(1);
        assertThat(result.getSkippedCount()).isGreaterThanOrEqualTo(4);

        assertThat(ftpTemplate.exists("/hidden_remote/legit.txt")).isTrue();
        assertThat(ftpTemplate.exists("/hidden_remote/desktop.ini")).isFalse();
        assertThat(ftpTemplate.exists("/hidden_remote/.DS_Store")).isFalse();
        assertThat(ftpTemplate.exists("/hidden_remote/Thumbs.db")).isFalse();
        assertThat(ftpTemplate.exists("/hidden_remote/._resource_fork")).isFalse();
    }

    @Test
    @DisplayName("测试批量上传空目录时远端目标根目录同步创建")
    void testUploadEmptyDirectoryCreatesRemoteDir(@TempDir Path tempDir) throws IOException {
        Path emptyDir = tempDir.resolve("empty_local_dir");
        Files.createDirectories(emptyDir);

        BatchTransferResult result = ftpTemplate.uploadDirectory(emptyDir.toFile(), "/empty_remote_target");
        assertThat(result.isAllSuccess()).isTrue();
        assertThat(result.getTotalFiles()).isEqualTo(0);
        assertThat(ftpTemplate.exists("/empty_remote_target")).isTrue();
    }

    @Test
    @DisplayName("测试批量上传时合法业务 .tmp 文件默认不被静默剔除")
    void testUploadDirectoryWithTmpFileAllowedByDefault(@TempDir Path tempDir) throws IOException {
        Path tmpDir = tempDir.resolve("tmp_file_dir");
        Files.createDirectories(tmpDir);
        Files.writeString(tmpDir.resolve("data.tmp"), "legit_tmp_content");

        BatchTransferResult result = ftpTemplate.uploadDirectory(tmpDir.toFile(), "/tmp_remote_target");
        assertThat(result.isAllSuccess()).isTrue();
        assertThat(result.getSuccessCount()).isEqualTo(1);
        assertThat(ftpTemplate.exists("/tmp_remote_target/data.tmp")).isTrue();
    }

    @Test
    @DisplayName("测试批量上传自定义谓词过滤 (Predicate<File>)")
    void testUploadDirectoryWithCustomFilter(@TempDir Path tempDir) throws IOException {
        Path filterDir = tempDir.resolve("filter_dir");
        Files.createDirectories(filterDir);
        Files.writeString(filterDir.resolve("data1.csv"), "1,2,3");
        Files.writeString(filterDir.resolve("data2.csv"), "4,5,6");
        Files.writeString(filterDir.resolve("readme.txt"), "readme");

        BatchTransferResult result = ftpTemplate.uploadDirectory(
                filterDir.toFile(), "/filter_remote", file -> file.getName().endsWith(".csv"), false);

        assertThat(result.isAllSuccess()).isTrue();
        assertThat(result.getTotalFiles()).isEqualTo(3);
        assertThat(result.getSuccessCount()).isEqualTo(2);
        assertThat(result.getSkippedCount()).isEqualTo(1);
        assertThat(ftpTemplate.exists("/filter_remote/data1.csv")).isTrue();
        assertThat(ftpTemplate.exists("/filter_remote/data2.csv")).isTrue();
        assertThat(ftpTemplate.exists("/filter_remote/readme.txt")).isFalse();
    }

    @Test
    @DisplayName("测试 Fail-Safe 容错批处理与 Fail-Fast 快速熔断双模机制")
    void testFailSafeAndFailFastBatchUpload(@TempDir Path tempDir) throws IOException {
        Path failDir = tempDir.resolve("fail_test_dir");
        Files.createDirectories(failDir.resolve("good"));
        Files.createDirectories(failDir.resolve("readonly"));
        Files.writeString(failDir.resolve("good/file1.txt"), "good1");
        Files.writeString(failDir.resolve("good/file2.txt"), "good2");
        Files.writeString(failDir.resolve("readonly/file3.txt"), "bad");

        // 在 FakeFtpServer 中将目标只读目录权限设为 NONE（拒绝写入）
        DirectoryEntry readonlyDir = new DirectoryEntry("/fail_remote/readonly");
        readonlyDir.setPermissions(Permissions.NONE);
        fakeFtpServer.getFileSystem().add(readonlyDir);

        // 1. Fail-Safe 模式 (failFast = false): 发生单文件失败不中断批次，记录明细并返回
        BatchTransferResult safeResult = ftpTemplate.uploadDirectory(failDir.toFile(), "/fail_remote", null, false);
        assertThat(safeResult.isAllSuccess()).isFalse();
        assertThat(safeResult.hasFailure()).isTrue();
        assertThat(safeResult.getSuccessCount()).isEqualTo(2);
        assertThat(safeResult.getFailureCount()).isEqualTo(1);
        assertThat(safeResult.getFailures()).hasSize(1);
        TransferFailureItem failure = safeResult.getFailures().get(0);
        assertThat(failure.getPath()).contains("file3.txt");
        assertThat(failure.getErrorMessage()).isNotBlank();
        assertThat(failure.getExceptionType()).isNotBlank();
        assertThat(failure.getFailedAt()).isNotNull();

        // 2. Fail-Fast 模式 (failFast = true): 遇到错误立即快速熔断抛出异常
        assertThatThrownBy(() -> ftpTemplate.uploadDirectory(failDir.toFile(), "/fail_remote", null, true))
                .isInstanceOf(FtpException.class);
    }

    @Test
    @DisplayName("测试目录同步边界异常校验")
    void testDirectorySyncValidationAndCornerCases(@TempDir Path tempDir) throws IOException {
        // 1. 本地目录不存在校验
        File nonExistent = new File(tempDir.toFile(), "not_exists_path");
        assertThatThrownBy(() -> ftpTemplate.uploadDirectory(nonExistent, "/remote"))
                .isInstanceOf(IllegalArgumentException.class);

        // 2. 本地目标不是目录校验
        Path singleFile = tempDir.resolve("file.txt");
        Files.writeString(singleFile, "hello");
        assertThatThrownBy(() -> ftpTemplate.uploadDirectory(singleFile.toFile(), "/remote"))
                .isInstanceOf(IllegalArgumentException.class);

        // 3. 远端目录不存在下载校验
        assertThatThrownBy(() -> ftpTemplate.downloadDirectory("/not_exist_remote_dir", tempDir.toFile()))
                .isInstanceOf(FtpException.class)
                .satisfies(e -> assertThat(((FtpException) e).getCode()).isEqualTo(FtpResultCode.FILE_NOT_FOUND.getCode()));

        // 4. 远端路径为单文件非目录下载校验
        ftpTemplate.writeString("/test_single.txt", "single_file");
        assertThatThrownBy(() -> ftpTemplate.downloadDirectory("/test_single.txt", tempDir.toFile()))
                .isInstanceOf(FtpException.class)
                .satisfies(e -> assertThat(((FtpException) e).getCode()).isEqualTo(FtpResultCode.INVALID_PATH.getCode()));
    }

    @Test
    @DisplayName("测试根目录删除熔断守卫 (Root Path Deletion Guard) 拦截所有根路径变体")
    void testDeleteRootDirectoryBlockedByGuard() {
        ftpTemplate.writeString("/test/protected.txt", "keep me");

        for (String rootCandidate : List.of("/", "///", "/./", "\\")) {
            assertThatThrownBy(() -> ftpTemplate.deleteDirectory(rootCandidate, true))
                    .isInstanceOf(FtpException.class)
                    .extracting(e -> ((FtpException) e).getCode())
                    .isEqualTo(FtpResultCode.INVALID_PATH.getCode());
        }
        assertThatThrownBy(() -> ftpTemplate.deleteDirectory("/", false))
                .isInstanceOf(FtpException.class)
                .extracting(e -> ((FtpException) e).getCode())
                .isEqualTo(FtpResultCode.INVALID_PATH.getCode());

        assertThat(ftpTemplate.exists("/test/protected.txt")).isTrue();
    }

    @Test
    @DisplayName("测试连接池耗尽超时精确转译为 POOL_EXHAUSTED (51008) 并保留原始 cause")
    void testPoolExhaustedThrowsPoolExhaustedException() throws Exception {
        GenericObjectPoolConfig<FTPClient> smallConfig = new GenericObjectPoolConfig<>();
        smallConfig.setMaxTotal(1);
        smallConfig.setMaxIdle(1);
        smallConfig.setMaxWait(Duration.ofMillis(120));
        GenericObjectPool<FTPClient> smallPool = new GenericObjectPool<>(
                new FtpClientPooledObjectFactory(properties), smallConfig);
        FtpTemplate smallTemplate = new FtpTemplate(smallPool, properties);

        CountDownLatch acquired = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        CompletableFuture<Void> holder = CompletableFuture.runAsync(() ->
                smallTemplate.execute(client -> {
                    acquired.countDown();
                    try {
                        release.await(5, TimeUnit.SECONDS);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    }
                    return true;
                }));

        try {
            assertThat(acquired.await(5, TimeUnit.SECONDS)).isTrue();
            assertThatThrownBy(() -> smallTemplate.exists("/test"))
                    .isInstanceOf(FtpException.class)
                    .satisfies(e -> {
                        assertThat(((FtpException) e).getCode()).isEqualTo(FtpResultCode.POOL_EXHAUSTED.getCode());
                        assertThat(e.getCause()).isInstanceOf(NoSuchElementException.class);
                    });
        } finally {
            release.countDown();
            holder.get(5, TimeUnit.SECONDS);
            smallPool.close();
        }
    }

    @Test
    @DisplayName("测试业务回调抛出 NoSuchElementException 与 activateObject 失败不被误判为 POOL_EXHAUSTED")
    void testNonPoolExhaustedNoSuchElementNotMisclassified() throws Exception {
        // 1. 业务回调内部抛出 NoSuchElementException 应归因 OPERATION_FAILED 而非 POOL_EXHAUSTED
        assertThatThrownBy(() -> ftpTemplate.execute(client -> {
            throw new NoSuchElementException("business element missing");
        }))
                .isInstanceOf(FtpException.class)
                .satisfies(e -> {
                    assertThat(((FtpException) e).getCode()).isEqualTo(FtpResultCode.OPERATION_FAILED.getCode());
                    assertThat(e.getCause()).isInstanceOf(NoSuchElementException.class);
                });

        // 2. activateObject 失败时，Commons Pool2 抛出包装了 FtpException(OPERATION_FAILED) 的 NoSuchElementException，
        //    FtpTemplate 应透传原始 OPERATION_FAILED 并触发坏连接销毁
        FtpClientPooledObjectFactory failingActivateFactory = new FtpClientPooledObjectFactory(properties) {
            @Override
            public void activateObject(org.apache.commons.pool2.PooledObject<FTPClient> p) {
                throw new FtpException(FtpResultCode.OPERATION_FAILED, "模拟借出复位失败");
            }
        };
        GenericObjectPoolConfig<FTPClient> config = new GenericObjectPoolConfig<>();
        config.setMaxTotal(2);
        GenericObjectPool<FTPClient> brokenPool = new GenericObjectPool<>(failingActivateFactory, config);
        FtpTemplate brokenTemplate = new FtpTemplate(brokenPool, properties);
        try {
            assertThatThrownBy(() -> brokenTemplate.execute(FTPClient::sendNoOp))
                    .isInstanceOf(FtpException.class)
                    .hasMessageContaining("模拟借出复位失败")
                    .extracting(e -> ((FtpException) e).getCode())
                    .isEqualTo(FtpResultCode.OPERATION_FAILED.getCode());
            assertThat(brokenPool.getDestroyedCount()).isGreaterThanOrEqualTo(1L);
        } finally {
            brokenPool.close();
        }
    }

    @Test
    @DisplayName("uploadResume: 部分 .tmp 分片应从断点 APPE 续传并原子转正，进度从断点推进至 100%")
    void uploadResume_partialTmpFile_resumesViaAppeAndRenames(@TempDir Path tempDir) throws Exception {
        byte[] fullContent = new byte[256 * 1024];
        for (int i = 0; i < fullContent.length; i++) {
            fullContent[i] = (byte) ((i * 31 + 7) & 0xFF);
        }
        int breakpoint = 100 * 1024;
        Path localFile = tempDir.resolve("resume-up.bin");
        Files.write(localFile, fullContent);

        FileEntry tmpEntry = new FileEntry("/test/resume-up.bin.tmp");
        tmpEntry.setContents(Arrays.copyOf(fullContent, breakpoint));
        fakeFtpServer.getFileSystem().add(tmpEntry);

        List<Double> percentages = new ArrayList<>();
        FtpProgressListener listener = (bytesTransferred, totalBytes, percentage) -> percentages.add(percentage);

        boolean uploaded = ftpTemplate.uploadResume("/test/resume-up.bin", localFile.toFile(), listener);

        assertThat(uploaded).isTrue();
        assertThat(fakeFtpServer.getFileSystem().exists("/test/resume-up.bin")).isTrue();
        assertThat(fakeFtpServer.getFileSystem().exists("/test/resume-up.bin.tmp")).isFalse();
        assertThat(ftpTemplate.downloadToBytes("/test/resume-up.bin")).isEqualTo(fullContent);
        assertThat(percentages).isNotEmpty();
        assertThat(percentages.get(0)).isGreaterThanOrEqualTo(39.0);
        assertThat(percentages.get(percentages.size() - 1)).isEqualTo(100.0);
        assertThat(percentages).allSatisfy(p -> assertThat(p).isBetween(0.0, 100.0));
    }

    @Test
    @DisplayName("uploadResume: 续传中途失败时应保留 .tmp 临时分片以便下次继续续传")
    void uploadResume_midFailurePreservesTmp_andNextResumeCompletes(@TempDir Path tempDir) throws Exception {
        byte[] fullContent = new byte[64 * 1024];
        for (int i = 0; i < fullContent.length; i++) {
            fullContent[i] = (byte) ((i + 19) & 0xFF);
        }
        int initialBreakpoint = 16 * 1024;
        Path localFile = tempDir.resolve("preserve-tmp.bin");
        Files.write(localFile, fullContent);

        FileEntry tmpEntry = new FileEntry("/test/preserve-tmp.bin.tmp");
        tmpEntry.setContents(Arrays.copyOf(fullContent, initialBreakpoint));
        fakeFtpServer.getFileSystem().add(tmpEntry);

        org.mockftpserver.core.command.CommandHandler originalAppe = fakeFtpServer.getCommandHandler("APPE");
        fakeFtpServer.setCommandHandler("APPE",
                new org.mockftpserver.core.command.StaticReplyCommandHandler(426, "Simulated APPE abort"));
        try {
            assertThatThrownBy(() -> ftpTemplate.uploadResume("/test/preserve-tmp.bin", localFile.toFile()))
                    .isInstanceOf(FtpException.class)
                    .extracting(e -> ((FtpException) e).getCode())
                    .isEqualTo(FtpResultCode.UPLOAD_FAILED.getCode());

            assertThat(fakeFtpServer.getFileSystem().exists("/test/preserve-tmp.bin.tmp")).isTrue();
            assertThat(fakeFtpServer.getFileSystem().exists("/test/preserve-tmp.bin")).isFalse();
        } finally {
            fakeFtpServer.setCommandHandler("APPE", originalAppe);
        }

        boolean completed = ftpTemplate.uploadResume("/test/preserve-tmp.bin", localFile.toFile());
        assertThat(completed).isTrue();
        assertThat(ftpTemplate.downloadToBytes("/test/preserve-tmp.bin")).isEqualTo(fullContent);
        assertThat(fakeFtpServer.getFileSystem().exists("/test/preserve-tmp.bin.tmp")).isFalse();
    }

    @Test
    @DisplayName("uploadResume: 远端脏分片大于本地文件大小时应清理并从零重传")
    void uploadResume_oversizedRemoteTmp_deletesDirtyAndReuploads(@TempDir Path tempDir) throws Exception {
        byte[] localContent = "fresh-small-payload".getBytes(StandardCharsets.UTF_8);
        Path localFile = tempDir.resolve("oversized.bin");
        Files.write(localFile, localContent);

        byte[] dirtyContent = new byte[4096];
        Arrays.fill(dirtyContent, (byte) 0x7A);
        FileEntry dirtyEntry = new FileEntry("/test/oversized.bin.tmp");
        dirtyEntry.setContents(dirtyContent);
        fakeFtpServer.getFileSystem().add(dirtyEntry);

        boolean uploaded = ftpTemplate.uploadResume("/test/oversized.bin", localFile.toFile());

        assertThat(uploaded).isTrue();
        assertThat(ftpTemplate.downloadToBytes("/test/oversized.bin")).isEqualTo(localContent);
        assertThat(fakeFtpServer.getFileSystem().exists("/test/oversized.bin.tmp")).isFalse();
    }

    @Test
    @DisplayName("uploadResume: 远端分片与本地等长且非空时应跳过传输直接原子转正并触发 100% 回调")
    void uploadResume_equalSizeTmp_skipsTransferRenamesAndNotifies100Percent(@TempDir Path tempDir) throws Exception {
        byte[] content = new byte[1024];
        Arrays.fill(content, (byte) 0x42);
        Path localFile = tempDir.resolve("equal.bin");
        Files.write(localFile, content);

        FileEntry tmpEntry = new FileEntry("/test/equal.bin.tmp");
        tmpEntry.setContents(content);
        fakeFtpServer.getFileSystem().add(tmpEntry);

        AtomicReference<Double> lastPercentage = new AtomicReference<>();
        boolean uploaded = ftpTemplate.uploadResume("/test/equal.bin", localFile.toFile(),
                (transferred, total, pct) -> lastPercentage.set(pct));

        assertThat(uploaded).isTrue();
        assertThat(fakeFtpServer.getFileSystem().exists("/test/equal.bin")).isTrue();
        assertThat(fakeFtpServer.getFileSystem().exists("/test/equal.bin.tmp")).isFalse();
        assertThat(ftpTemplate.downloadToBytes("/test/equal.bin")).isEqualTo(content);
        assertThat(lastPercentage.get()).isEqualTo(100.0);
    }

    @Test
    @DisplayName("uploadResume: 零字节空文件应正常上传完成并回调 100%")
    void uploadResume_zeroByteFile_uploadsAndNotifies100Percent(@TempDir Path tempDir) throws Exception {
        Path emptyFile = tempDir.resolve("empty.bin");
        Files.write(emptyFile, new byte[0]);

        AtomicReference<Double> lastPercentage = new AtomicReference<>();
        boolean uploaded = ftpTemplate.uploadResume("/test/empty-resume.bin", emptyFile.toFile(),
                (transferred, total, pct) -> lastPercentage.set(pct));

        assertThat(uploaded).isTrue();
        assertThat(ftpTemplate.getFileInfo("/test/empty-resume.bin").getSize()).isZero();
        assertThat(lastPercentage.get()).isEqualTo(100.0);
    }

    @Test
    @DisplayName("downloadResume: 本地已存在部分文件时应断点续传并完整对齐字节与进度")
    void downloadResume_partialLocalFile_resumesAndMatchesSource(@TempDir Path tempDir) throws Exception {
        byte[] remoteContent = new byte[128 * 1024];
        for (int i = 0; i < remoteContent.length; i++) {
            remoteContent[i] = (byte) ((i * 17 + 3) & 0xFF);
        }
        FileEntry remoteEntry = new FileEntry("/test/resume-down.bin");
        remoteEntry.setContents(remoteContent);
        fakeFtpServer.getFileSystem().add(remoteEntry);

        int localOffset = 40 * 1024;
        Path localTarget = tempDir.resolve("resume-down.bin");
        Files.write(localTarget, Arrays.copyOf(remoteContent, localOffset));

        List<Double> percentages = new ArrayList<>();
        boolean downloaded = ftpTemplate.downloadResume("/test/resume-down.bin", localTarget.toFile(),
                (transferred, total, pct) -> percentages.add(pct));

        assertThat(downloaded).isTrue();
        assertThat(Files.readAllBytes(localTarget)).isEqualTo(remoteContent);
        assertThat(percentages).isNotEmpty();
        assertThat(percentages.get(0)).isGreaterThanOrEqualTo(31.0);
        assertThat(percentages.get(percentages.size() - 1)).isEqualTo(100.0);
        assertThat(percentages).allSatisfy(p -> assertThat(p).isBetween(0.0, 100.0));
    }

    @Test
    @DisplayName("downloadResume: 本地文件已完整等长时应跳过下载并触发 100% 回调")
    void downloadResume_equalSizeLocalFile_skipsTransferAndNotifies100Percent(@TempDir Path tempDir) throws Exception {
        byte[] remoteContent = "complete-local-content".getBytes(StandardCharsets.UTF_8);
        FileEntry remoteEntry = new FileEntry("/test/equal-down.bin");
        remoteEntry.setContents(remoteContent);
        fakeFtpServer.getFileSystem().add(remoteEntry);

        Path localTarget = tempDir.resolve("equal-down.bin");
        Files.write(localTarget, remoteContent);

        AtomicReference<Double> lastPercentage = new AtomicReference<>();
        boolean downloaded = ftpTemplate.downloadResume("/test/equal-down.bin", localTarget.toFile(),
                (transferred, total, pct) -> lastPercentage.set(pct));

        assertThat(downloaded).isTrue();
        assertThat(Files.readAllBytes(localTarget)).isEqualTo(remoteContent);
        assertThat(lastPercentage.get()).isEqualTo(100.0);
    }

    @Test
    @DisplayName("downloadResume: 本地文件大于远端文件时应清零偏移量并全量覆盖下载")
    void downloadResume_oversizedLocalFile_truncatesAndRedownloads(@TempDir Path tempDir) throws Exception {
        byte[] remoteContent = "small-remote-source".getBytes(StandardCharsets.UTF_8);
        FileEntry remoteEntry = new FileEntry("/test/shrink-down.bin");
        remoteEntry.setContents(remoteContent);
        fakeFtpServer.getFileSystem().add(remoteEntry);

        byte[] dirtyLocal = new byte[2048];
        Arrays.fill(dirtyLocal, (byte) 0x55);
        Path localTarget = tempDir.resolve("shrink-down.bin");
        Files.write(localTarget, dirtyLocal);

        boolean downloaded = ftpTemplate.downloadResume("/test/shrink-down.bin", localTarget.toFile());

        assertThat(downloaded).isTrue();
        assertThat(Files.readAllBytes(localTarget)).isEqualTo(remoteContent);
    }

    @Test
    @DisplayName("downloadResume: 远端路径不存在或为目录时应抛出对应 FtpException")
    void downloadResume_missingOrDirectoryRemote_throwsExpectedException(@TempDir Path tempDir) {
        Path localTarget = tempDir.resolve("err.bin");
        assertThatThrownBy(() -> ftpTemplate.downloadResume("/test/no-such-file.bin", localTarget.toFile()))
                .isInstanceOf(FtpException.class)
                .extracting(e -> ((FtpException) e).getCode())
                .isEqualTo(FtpResultCode.FILE_NOT_FOUND.getCode());

        assertThatThrownBy(() -> ftpTemplate.downloadResume("/test", localTarget.toFile()))
                .isInstanceOf(FtpException.class)
                .extracting(e -> ((FtpException) e).getCode())
                .isEqualTo(FtpResultCode.INVALID_PATH.getCode());
    }

    @Test
    @DisplayName("calculateHash: 零落盘流式计算 MD5 与 SHA-256 小写十六进制摘要应 100% 匹配")
    void calculateHash_md5AndSha256_computesLowercaseHexWithoutTempFile() throws Exception {
        byte[] payload = new byte[64 * 1024];
        for (int i = 0; i < payload.length; i++) {
            payload[i] = (byte) ((i * 13 + 5) & 0xFF);
        }
        ftpTemplate.upload("/test/checksum-payload.bin", payload);

        String expectedMd5 = HexFormat.of().formatHex(MessageDigest.getInstance("MD5").digest(payload));
        String expectedSha256 = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(payload));

        assertThat(ftpTemplate.calculateHash("/test/checksum-payload.bin", "MD5")).isEqualTo(expectedMd5);
        assertThat(ftpTemplate.calculateHash("/test/checksum-payload.bin", "SHA-256")).isEqualTo(expectedSha256);
    }

    @Test
    @DisplayName("calculateHash: 缺失文件与非法摘要算法应快速失败抛出预期异常")
    void calculateHash_missingFileOrUnsupportedAlgorithm_throwsExpectedException() {
        ftpTemplate.upload("/test/hash-err.txt", "hash".getBytes(StandardCharsets.UTF_8));

        assertThatThrownBy(() -> ftpTemplate.calculateHash("/test/not-found-hash.bin", "SHA-256"))
                .isInstanceOf(FtpException.class)
                .extracting(e -> ((FtpException) e).getCode())
                .isEqualTo(FtpResultCode.FILE_NOT_FOUND.getCode());

        assertThatThrownBy(() -> ftpTemplate.calculateHash("/test/hash-err.txt", "UNKNOWN-HASH-ALGO"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("verifyHash: 大小写不敏感常量时间摘要比对与篡改拦截")
    void verifyHash_caseInsensitiveAndConstantTime_matchesOrReturnsFalse() throws Exception {
        byte[] payload = "settlement-hash-verify-body".getBytes(StandardCharsets.UTF_8);
        ftpTemplate.upload("/test/verify-body.txt", payload);
        String expectedSha256 = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(payload));

        assertThat(ftpTemplate.verifyHash("/test/verify-body.txt",
                "  " + expectedSha256.toUpperCase(Locale.ROOT) + "  ", "SHA-256")).isTrue();

        String tampered = "0".repeat(expectedSha256.length() - 1) + "1";
        assertThat(ftpTemplate.verifyHash("/test/verify-body.txt", tampered, "SHA-256")).isFalse();
        assertThat(ftpTemplate.verifyHash("/test/verify-body.txt", "deadbeef", "SHA-256")).isFalse();
    }

    @Test
    @DisplayName("uploadWithSentinel: 默认 .ok 与自定义信标后缀应写入 SHA-256 摘要、文件名与字节长度")
    void uploadWithSentinel_defaultAndCustomOkSuffix_writesSha256DigestLine(@TempDir Path tempDir) throws Exception {
        byte[] payload = "bill_id,amount\n1001,99.50\n".getBytes(StandardCharsets.UTF_8);
        Path localFile = tempDir.resolve("settle-20261117.csv");
        Files.write(localFile, payload);
        String expectedSha256 = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(payload));

        String okSentinel = ftpTemplate.uploadWithSentinel("/test/settle-default.csv", localFile.toFile(), null);
        assertThat(okSentinel).isEqualTo("/test/settle-default.csv.ok");
        assertThat(ftpTemplate.downloadToBytes("/test/settle-default.csv")).isEqualTo(payload);
        assertThat(ftpTemplate.readString(okSentinel))
                .isEqualTo(expectedSha256 + "  settle-default.csv  " + payload.length + "\n");

        String doneSentinel = ftpTemplate.uploadWithSentinel("/test/settle-custom.csv", localFile.toFile(), "done");
        assertThat(doneSentinel).isEqualTo("/test/settle-custom.csv.done");
        assertThat(ftpTemplate.readString(doneSentinel))
                .isEqualTo(expectedSha256 + "  settle-custom.csv  " + payload.length + "\n");
    }

    @Test
    @DisplayName("uploadWithSentinel: .sha256 与 .md5 信标后缀应生成标准校验和文件格式")
    void uploadWithSentinel_digestSuffix_writesStandardChecksumLine(@TempDir Path tempDir) throws Exception {
        byte[] payload = "reconciliation-ledger-batch".getBytes(StandardCharsets.UTF_8);
        Path localFile = tempDir.resolve("ledger.dat");
        Files.write(localFile, payload);
        String expectedSha256 = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(payload));
        String expectedMd5 = HexFormat.of().formatHex(MessageDigest.getInstance("MD5").digest(payload));

        String shaSentinel = ftpTemplate.uploadWithSentinel("/test/ledger-a.dat", localFile.toFile(), ".sha256");
        assertThat(shaSentinel).isEqualTo("/test/ledger-a.dat.sha256");
        assertThat(ftpTemplate.readString(shaSentinel)).isEqualTo(expectedSha256 + "  ledger-a.dat\n");

        String md5Sentinel = ftpTemplate.uploadWithSentinel("/test/ledger-b.dat", localFile.toFile(), "MD5");
        assertThat(md5Sentinel).isEqualTo("/test/ledger-b.dat.MD5");
        assertThat(ftpTemplate.readString(md5Sentinel)).isEqualTo(expectedMd5 + "  ledger-b.dat\n");
    }

    @Test
    @DisplayName("uploadWithSentinel: 非法路径穿越信标后缀应直接拦截抛出 INVALID_PATH")
    void uploadWithSentinel_traversalSuffix_throwsInvalidPath(@TempDir Path tempDir) throws Exception {
        Path localFile = tempDir.resolve("safe.txt");
        Files.writeString(localFile, "safe", StandardCharsets.UTF_8);

        assertThatThrownBy(() -> ftpTemplate.uploadWithSentinel("/test/safe.txt", localFile.toFile(), "../evil"))
                .isInstanceOf(FtpException.class)
                .extracting(e -> ((FtpException) e).getCode())
                .isEqualTo(FtpResultCode.INVALID_PATH.getCode());

        assertThatThrownBy(() -> ftpTemplate.uploadWithSentinel("/test/safe.txt", localFile.toFile(), "dir/ok"))
                .isInstanceOf(FtpException.class)
                .extracting(e -> ((FtpException) e).getCode())
                .isEqualTo(FtpResultCode.INVALID_PATH.getCode());
    }

    @Test
    @DisplayName("downloadResume: 服务端 502 拒绝 REST 时应回退全量拉取跳过前缀；零字节文件应自动落地本地空文件")
    void downloadResume_restRejected502_fallsBackCleanlyAndCreatesEmptyFile(@TempDir Path tempDir) throws Exception {
        byte[] remoteContent = new byte[48 * 1024];
        for (int i = 0; i < remoteContent.length; i++) {
            remoteContent[i] = (byte) ((i * 7 + 13) & 0xFF);
        }
        ftpTemplate.upload("/test/rest502.bin", remoteContent);
        ftpTemplate.upload("/test/zero-down.bin", new byte[0]);

        Path partialLocal = tempDir.resolve("rest502.bin");
        Files.write(partialLocal, Arrays.copyOf(remoteContent, 16 * 1024));

        org.mockftpserver.core.command.CommandHandler originalRest = fakeFtpServer.getCommandHandler("REST");
        fakeFtpServer.setCommandHandler("REST",
                new org.mockftpserver.core.command.StaticReplyCommandHandler(502, "REST not implemented"));
        try {
            boolean downloaded = ftpTemplate.downloadResume("/test/rest502.bin", partialLocal.toFile());
            assertThat(downloaded).isTrue();
            assertThat(Files.readAllBytes(partialLocal)).isEqualTo(remoteContent);
        } finally {
            fakeFtpServer.setCommandHandler("REST", originalRest);
        }

        Path zeroTarget = tempDir.resolve("zero-down.bin");
        AtomicReference<Double> pctRef = new AtomicReference<>();
        boolean zeroDownloaded = ftpTemplate.downloadResume("/test/zero-down.bin", zeroTarget.toFile(),
                (transferred, total, pct) -> pctRef.set(pct));
        assertThat(zeroDownloaded).isTrue();
        assertThat(Files.exists(zeroTarget)).isTrue();
        assertThat(Files.size(zeroTarget)).isZero();
        assertThat(pctRef.get()).isEqualTo(100.0);
    }
}
