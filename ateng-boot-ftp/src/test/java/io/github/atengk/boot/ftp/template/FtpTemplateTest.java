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

import java.io.*;
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
}
