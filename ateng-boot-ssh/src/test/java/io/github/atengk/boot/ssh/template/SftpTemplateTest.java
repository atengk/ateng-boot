package io.github.atengk.boot.ssh.template;

import io.github.atengk.boot.ssh.exception.SshOperationException;
import io.github.atengk.boot.ssh.exception.SshResultCode;
import io.github.atengk.boot.ssh.listener.SftpProgressListener;
import io.github.atengk.boot.ssh.model.SftpFileInfo;
import io.github.atengk.boot.ssh.model.SshServerInfo;
import io.github.atengk.boot.ssh.pool.SshSessionPool;
import io.github.atengk.boot.ssh.properties.SshPoolProperties;
import io.github.atengk.boot.ssh.properties.SshProperties;
import io.github.atengk.boot.ssh.registry.SshClientRegistry;
import org.apache.sshd.client.SshClient;
import org.apache.sshd.client.keyverifier.AcceptAllServerKeyVerifier;
import org.apache.sshd.common.file.virtualfs.VirtualFileSystemFactory;
import org.apache.sshd.server.SshServer;
import org.apache.sshd.server.keyprovider.SimpleGeneratorHostKeyProvider;
import org.apache.sshd.sftp.server.SftpSubsystemFactory;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collections;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * SftpTemplate 核心文件传输与治理门面单元测试
 *
 * @author Ateng
 * @since 2026-10-23
 */
@DisplayName("SftpTemplate 核心文件传输与治理门面单元测试")
class SftpTemplateTest {

    private SshServer sshServer;
    private SshClient sshClient;
    private SshSessionPool sessionPool;
    private SshClientRegistry clientRegistry;
    private SshProperties sshProperties;
    private SftpTemplate sftpTemplate;
    private int serverPort;
    private Path remoteRoot;

    @BeforeEach
    void setUp(@TempDir Path tempDir) throws IOException {
        remoteRoot = tempDir.resolve("sftp-root");
        Files.createDirectories(remoteRoot);

        // 1. 启动嵌入式 SSH 服务端，挂载 SFTP 子系统与虚拟根目录
        sshServer = SshServer.setUpDefaultServer();
        sshServer.setHost("127.0.0.1");
        sshServer.setPort(0);
        sshServer.setKeyPairProvider(new SimpleGeneratorHostKeyProvider(tempDir.resolve("hostkey.ser")));
        sshServer.setPasswordAuthenticator((username, password, session) ->
                "testuser".equals(username) && "testpass".equals(password));
        sshServer.setSubsystemFactories(Collections.singletonList(new SftpSubsystemFactory()));
        sshServer.setFileSystemFactory(new VirtualFileSystemFactory(remoteRoot));
        sshServer.start();
        serverPort = sshServer.getPort();

        // 2. 启动客户端与会话池
        sshClient = SshClient.setUpDefaultClient();
        sshClient.setServerKeyVerifier(AcceptAllServerKeyVerifier.INSTANCE);
        sshClient.start();

        sshProperties = new SshProperties();
        sshProperties.setHost("127.0.0.1");
        sshProperties.setPort(serverPort);
        sshProperties.setUsername("testuser");
        sshProperties.setPassword("testpass");

        SshPoolProperties poolProps = new SshPoolProperties();
        poolProps.setMaxSessions(10);
        sessionPool = new SshSessionPool(sshClient, poolProps);

        clientRegistry = new SshClientRegistry();
        clientRegistry.register(SshClientRegistry.DEFAULT_SERVER, SshServerInfo.fromProperties(sshProperties));

        sftpTemplate = new SftpTemplate(sessionPool, clientRegistry, sshProperties);
    }

    @AfterEach
    void tearDown() {
        if (sessionPool != null) {
            sessionPool.close();
        }
        if (sshClient != null) {
            sshClient.stop();
        }
        if (sshServer != null) {
            try {
                sshServer.stop(true);
            } catch (IOException ignored) {
            }
        }
    }

    @Test
    @DisplayName("测试通过字节数组流式上传并重新下载验证内容一致")
    void testUploadAndDownloadByteArray() {
        String content = "Hello SFTP Streaming Engine! 2026-10-23";
        byte[] bytes = content.getBytes(StandardCharsets.UTF_8);

        sftpTemplate.upload("/hello.txt", bytes);

        byte[] downloaded = sftpTemplate.downloadToBytes("/hello.txt");
        assertThat(new String(downloaded, StandardCharsets.UTF_8)).isEqualTo(content);
    }

    @Test
    @DisplayName("测试原子上传机制：成功写入并重命名覆盖目标文件")
    void testAtomicUploadWorkflow() {
        String path = "/atomic-test.txt";
        byte[] initial = "version 1".getBytes(StandardCharsets.UTF_8);
        byte[] updated = "version 2 with atomic rename".getBytes(StandardCharsets.UTF_8);

        sftpTemplate.upload(path, initial);
        assertThat(new String(sftpTemplate.downloadToBytes(path), StandardCharsets.UTF_8)).isEqualTo("version 1");

        // 覆盖更新
        sftpTemplate.upload(path, updated);
        assertThat(new String(sftpTemplate.downloadToBytes(path), StandardCharsets.UTF_8)).isEqualTo("version 2 with atomic rename");
    }

    @Test
    @DisplayName("测试输入流与输出流流式传输以及函数式 Consumer 消费")
    void testUploadAndDownloadStream() {
        String path = "/stream.dat";
        String testData = "Streaming data content for verification";
        ByteArrayInputStream bais = new ByteArrayInputStream(testData.getBytes(StandardCharsets.UTF_8));

        sftpTemplate.upload(path, bais);

        // 1. download to OutputStream
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        sftpTemplate.download(path, baos);
        assertThat(baos.toString(StandardCharsets.UTF_8)).isEqualTo(testData);

        // 2. download with Consumer<InputStream>
        AtomicBoolean consumed = new AtomicBoolean(false);
        sftpTemplate.download(path, (InputStream in) -> {
            try {
                byte[] readBytes = in.readAllBytes();
                assertThat(new String(readBytes, StandardCharsets.UTF_8)).isEqualTo(testData);
                consumed.set(true);
            } catch (IOException e) {
                throw new RuntimeException(e);
            }
        });
        assertThat(consumed.get()).isTrue();
    }

    @Test
    @DisplayName("测试本地磁盘文件上传与下载落地本地磁盘文件")
    void testUploadAndDownloadFile(@TempDir Path localTemp) throws IOException {
        Path localSource = localTemp.resolve("source.txt");
        String fileContent = "Disk file content 123456789";
        Files.writeString(localSource, fileContent);

        // 上传到远端
        sftpTemplate.upload("/remote-file.txt", localSource.toFile());

        // 下载到新本地文件
        Path localDest = localTemp.resolve("dest.txt");
        sftpTemplate.downloadToFile("/remote-file.txt", localDest.toFile());

        assertThat(Files.readString(localDest)).isEqualTo(fileContent);
    }

    @Test
    @DisplayName("测试上传深层嵌套路径时自动递归创建父目录")
    void testAutoMkdirsOnUpload() {
        String nestedPath = "/nested/sub1/sub2/app.log";
        byte[] logData = "Application log initialized".getBytes(StandardCharsets.UTF_8);

        sftpTemplate.upload(nestedPath, logData);

        assertThat(sftpTemplate.exists(nestedPath)).isTrue();
        assertThat(sftpTemplate.exists("/nested/sub1/sub2")).isTrue();
        assertThat(new String(sftpTemplate.downloadToBytes(nestedPath), StandardCharsets.UTF_8))
                .isEqualTo("Application log initialized");
    }

    @Test
    @DisplayName("测试 stat 文件元数据提取与 exists 存在性检查")
    void testStatAndExists() {
        String path = "/stat-meta.txt";
        byte[] data = "Metadata test".getBytes(StandardCharsets.UTF_8);
        sftpTemplate.upload(path, data);

        assertThat(sftpTemplate.exists(path)).isTrue();
        assertThat(sftpTemplate.exists("/not-exist-file.txt")).isFalse();

        SftpFileInfo info = sftpTemplate.stat(path);
        assertThat(info).isNotNull();
        assertThat(info.getFilename()).isEqualTo("stat-meta.txt");
        assertThat(info.getSize()).isEqualTo(data.length);
        assertThat(info.isRegularFile()).isTrue();
        assertThat(info.isDirectory()).isFalse();
        assertThat(info.getPermissionsString()).isNotBlank();
        assertThat(info.getModifiedTime()).isNotNull();
    }

    @Test
    @DisplayName("测试 delete 正常删除与删除不存在文件保底返回 false")
    void testDelete() {
        String path = "/to-delete.txt";
        sftpTemplate.upload(path, "delete me".getBytes(StandardCharsets.UTF_8));
        assertThat(sftpTemplate.exists(path)).isTrue();

        boolean deleted = sftpTemplate.delete(path);
        assertThat(deleted).isTrue();
        assertThat(sftpTemplate.exists(path)).isFalse();

        // 再次删除已不存在的文件，应平滑返回 false 而非抛出异常
        boolean deleteAgain = sftpTemplate.delete(path);
        assertThat(deleteAgain).isFalse();
    }

    @Test
    @DisplayName("测试 chmod 权限修改与 mkdir/mkdirs 目录创建")
    void testChmodAndMkdirs() {
        sftpTemplate.mkdirs("/perm-dir/inner");
        assertThat(sftpTemplate.exists("/perm-dir/inner")).isTrue();

        String filePath = "/perm-dir/inner/data.txt";
        sftpTemplate.upload(filePath, "data".getBytes(StandardCharsets.UTF_8));

        // 修改权限为 0755
        sftpTemplate.chmod(filePath, 0755);
        SftpFileInfo info = sftpTemplate.stat(filePath);
        assertThat(info.getPermissions()).isGreaterThan(0);
        if (java.nio.file.FileSystems.getDefault().supportedFileAttributeViews().contains("posix")) {
            assertThat(info.getPermissions() & 0777).isEqualTo(0755);
        }
    }

    @Test
    @DisplayName("测试路径穿越攻击防卫：包含 ../ 等非法路径时直接 Fail-Fast 拦截")
    void testPathTraversalGuard() {
        assertThatThrownBy(() -> sftpTemplate.upload("/sub/../../etc/passwd", "hack".getBytes(StandardCharsets.UTF_8)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Path Traversal");

        assertThatThrownBy(() -> sftpTemplate.download("../secret.key", new ByteArrayOutputStream()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Path Traversal");

        assertThatThrownBy(() -> sftpTemplate.stat(".."))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Path Traversal");
    }

    @Test
    @DisplayName("测试动态传入 SshServerInfo 值对象时对称执行成功")
    void testDynamicServerInfoRouting() {
        SshServerInfo dynamicServer = SshServerInfo.builder()
                .host("127.0.0.1")
                .port(serverPort)
                .username("testuser")
                .password("testpass")
                .build();

        String path = "/dynamic-route.txt";
        sftpTemplate.upload(dynamicServer, path, "dynamic server info content".getBytes(StandardCharsets.UTF_8));

        byte[] result = sftpTemplate.downloadToBytes(dynamicServer, path);
        assertThat(new String(result, StandardCharsets.UTF_8)).isEqualTo("dynamic server info content");
        assertThat(sftpTemplate.exists(dynamicServer, path)).isTrue();
        assertThat(sftpTemplate.delete(dynamicServer, path)).isTrue();
    }

    @Test
    @DisplayName("测试下载不存在的文件时抛出 SFTP_FILE_NOT_FOUND 语义化异常")
    void testDownloadNonExistentThrowsSftpFileNotFound() {
        assertThatThrownBy(() -> sftpTemplate.downloadToBytes("/non-existent-file-404.txt"))
                .isInstanceOf(SshOperationException.class)
                .satisfies(ex -> {
                    SshOperationException soe = (SshOperationException) ex;
                    assertThat(soe.getResultCode()).isEqualTo(SshResultCode.SFTP_FILE_NOT_FOUND);
                });
    }

    @Test
    @DisplayName("测试大文件流式上传与下载挂载 SftpProgressListener 实时监听进度")
    void testStreamingProgressListener() {
        byte[] payload = new byte[100 * 1024]; // 100 KB
        for (int i = 0; i < payload.length; i++) {
            payload[i] = (byte) (i % 128);
        }
        String path = "/progress-test.bin";

        // 1. 上传监听
        java.util.concurrent.atomic.AtomicLong uploadBytes = new java.util.concurrent.atomic.AtomicLong(0);
        java.util.concurrent.atomic.AtomicLong uploadTotal = new java.util.concurrent.atomic.AtomicLong(0);
        sftpTemplate.upload(path, payload, (transferred, total) -> {
            uploadBytes.set(transferred);
            uploadTotal.set(total);
        });

        assertThat(uploadBytes.get()).isEqualTo(payload.length);
        assertThat(uploadTotal.get()).isEqualTo(payload.length);

        // 2. 下载监听
        java.util.concurrent.atomic.AtomicLong downloadBytes = new java.util.concurrent.atomic.AtomicLong(0);
        java.util.concurrent.atomic.AtomicLong downloadTotal = new java.util.concurrent.atomic.AtomicLong(0);
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        sftpTemplate.download(path, baos, (transferred, total) -> {
            downloadBytes.set(transferred);
            downloadTotal.set(total);
        });

        assertThat(downloadBytes.get()).isEqualTo(payload.length);
        assertThat(downloadTotal.get()).isEqualTo(payload.length);
        assertThat(baos.toByteArray()).isEqualTo(payload);
    }

    @Test
    @DisplayName("测试 list 检索指定目录下的全部文件与子目录元数据")
    void testListDirectory() {
        String baseDir = "/list-dir";
        sftpTemplate.upload(baseDir + "/file1.txt", "1".getBytes(StandardCharsets.UTF_8));
        sftpTemplate.upload(baseDir + "/file2.txt", "22".getBytes(StandardCharsets.UTF_8));
        sftpTemplate.mkdirs(baseDir + "/child-dir");

        java.util.List<SftpFileInfo> entries = sftpTemplate.list(baseDir);
        assertThat(entries).hasSize(3);

        java.util.Map<String, SftpFileInfo> map = new java.util.HashMap<>();
        for (SftpFileInfo entry : entries) {
            map.put(entry.getFilename(), entry);
        }

        assertThat(map).containsKey("file1.txt");
        assertThat(map).containsKey("file2.txt");
        assertThat(map).containsKey("child-dir");

        assertThat(map.get("file1.txt").isRegularFile()).isTrue();
        assertThat(map.get("file1.txt").getSize()).isEqualTo(1L);

        assertThat(map.get("child-dir").isDirectory()).isTrue();
    }

    @Test
    @DisplayName("测试 deleteRecursively 深度优先递归删除多层非空目录")
    void testDeleteRecursively() {
        String rootDir = "/tree-to-delete";
        sftpTemplate.upload(rootDir + "/sub1/fileA.txt", "A".getBytes(StandardCharsets.UTF_8));
        sftpTemplate.upload(rootDir + "/sub1/sub2/fileB.txt", "B".getBytes(StandardCharsets.UTF_8));
        sftpTemplate.mkdirs(rootDir + "/empty-sub");

        assertThat(sftpTemplate.exists(rootDir + "/sub1/sub2/fileB.txt")).isTrue();
        assertThat(sftpTemplate.exists(rootDir + "/empty-sub")).isTrue();

        boolean result = sftpTemplate.deleteRecursively(rootDir);
        assertThat(result).isTrue();

        assertThat(sftpTemplate.exists(rootDir)).isFalse();
        assertThat(sftpTemplate.exists(rootDir + "/sub1/sub2/fileB.txt")).isFalse();
    }

    @Test
    @DisplayName("测试 deleteRecursively 遇到高危受保护根目录时触发 Fail-Fast 异常拦截（包括尾斜杠绕过防卫）")
    void testDeleteRecursivelyHighRiskRootGuard() {
        assertThatThrownBy(() -> sftpTemplate.deleteRecursively("/"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("禁止递归删除系统关键受保护目录");

        assertThatThrownBy(() -> sftpTemplate.deleteRecursively("/etc"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("禁止递归删除系统关键受保护目录");

        assertThatThrownBy(() -> sftpTemplate.deleteRecursively("/etc/"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("禁止递归删除系统关键受保护目录");

        assertThatThrownBy(() -> sftpTemplate.deleteRecursively("/root"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("禁止递归删除系统关键受保护目录");

        assertThatThrownBy(() -> sftpTemplate.deleteRecursively("/root///"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("禁止递归删除系统关键受保护目录");

        assertThatThrownBy(() -> sftpTemplate.deleteRecursively("/usr/"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("禁止递归删除系统关键受保护目录");

        assertThatThrownBy(() -> sftpTemplate.deleteRecursively(""))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("测试 uploadDirectory 与 downloadDirectory 整包双向同步目录树（空目录镜像 + 宿主隐性文件过滤）")
    void testUploadAndDownloadDirectory(@TempDir Path tempLocal) throws IOException {
        Path sourceLocal = tempLocal.resolve("source-tree");
        Files.createDirectories(sourceLocal.resolve("empty-child"));
        Files.createDirectories(sourceLocal.resolve("nested/deep"));

        Files.writeString(sourceLocal.resolve("root-file.txt"), "Root content");
        Files.writeString(sourceLocal.resolve("nested/deep/deep-file.txt"), "Deep content");
        // 隐性系统文件（应被自动忽略）
        Files.writeString(sourceLocal.resolve("desktop.ini"), "[ViewState]");
        Files.writeString(sourceLocal.resolve(".DS_Store"), "macOS metadata");

        String remoteSyncDir = "/synced-tree";

        // 1. 上传整棵目录树
        sftpTemplate.uploadDirectory(sourceLocal, remoteSyncDir);

        assertThat(sftpTemplate.exists(remoteSyncDir + "/root-file.txt")).isTrue();
        assertThat(sftpTemplate.exists(remoteSyncDir + "/nested/deep/deep-file.txt")).isTrue();
        assertThat(sftpTemplate.exists(remoteSyncDir + "/empty-child")).isTrue();
        // 校验隐性文件被成功过滤
        assertThat(sftpTemplate.exists(remoteSyncDir + "/desktop.ini")).isFalse();
        assertThat(sftpTemplate.exists(remoteSyncDir + "/.DS_Store")).isFalse();

        // 2. 下载整棵目录树至新本地目录
        Path destLocal = tempLocal.resolve("dest-tree");
        sftpTemplate.downloadDirectory(remoteSyncDir, destLocal);

        assertThat(Files.readString(destLocal.resolve("root-file.txt"))).isEqualTo("Root content");
        assertThat(Files.readString(destLocal.resolve("nested/deep/deep-file.txt"))).isEqualTo("Deep content");
        assertThat(Files.exists(destLocal.resolve("empty-child"))).isTrue();
        assertThat(Files.isDirectory(destLocal.resolve("empty-child"))).isTrue();
        assertThat(Files.exists(destLocal.resolve("desktop.ini"))).isFalse();
        assertThat(Files.exists(destLocal.resolve(".DS_Store"))).isFalse();
    }

    @Test
    @DisplayName("测试 append 契约：文件追加与不存在文件/缺失父目录递归自动创建")
    void testAppend() {
        String appendPath = "/test-append/deep/log.txt";

        // 1. 目标文件及父级目录不存在时，自动创建并写入首次数据
        sftpTemplate.append(appendPath, "Line 1\n".getBytes(StandardCharsets.UTF_8));
        assertThat(sftpTemplate.exists(appendPath)).isTrue();
        assertThat(sftpTemplate.readString(appendPath)).isEqualTo("Line 1\n");

        // 2. 目标文件存在时，InputStream 追加写入
        sftpTemplate.append(appendPath, new ByteArrayInputStream("Line 2\n".getBytes(StandardCharsets.UTF_8)));
        assertThat(sftpTemplate.readString(appendPath)).isEqualTo("Line 1\nLine 2\n");

        // 3. 目标文件存在时，byte[] 追加写入
        sftpTemplate.append(appendPath, "Line 3\n".getBytes(StandardCharsets.UTF_8));
        assertThat(sftpTemplate.readString(appendPath)).isEqualTo("Line 1\nLine 2\nLine 3\n");

        // 4. 防御性校验：null 入参快速失败
        assertThatThrownBy(() -> sftpTemplate.append(appendPath, (byte[]) null))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> sftpTemplate.append(appendPath, (InputStream) null))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("测试 rename 与 move 契约：同级重命名、跨目录移动、自动建目录与穿越防御")
    void testRenameAndMove() {
        String originalFile = "/test-move/original.txt";
        sftpTemplate.writeString(originalFile, "Hello Move");

        // 1. 同级重命名 rename
        String renamedFile = "/test-move/renamed.txt";
        sftpTemplate.rename(originalFile, renamedFile);
        assertThat(sftpTemplate.exists(originalFile)).isFalse();
        assertThat(sftpTemplate.exists(renamedFile)).isTrue();
        assertThat(sftpTemplate.readString(renamedFile)).isEqualTo("Hello Move");

        // 2. 跨目录移动 move（目标目录不存在时自动创建父级目录）
        String movedFile = "/test-move/nested/subdir/moved.txt";
        sftpTemplate.move(renamedFile, movedFile);
        assertThat(sftpTemplate.exists(renamedFile)).isFalse();
        assertThat(sftpTemplate.exists(movedFile)).isTrue();
        assertThat(sftpTemplate.readString(movedFile)).isEqualTo("Hello Move");

        // 3. 移动至以 / 结尾的目标目录
        String targetDir = "/test-move/dest-folder/";
        sftpTemplate.move(movedFile, targetDir);
        assertThat(sftpTemplate.exists(movedFile)).isFalse();
        assertThat(sftpTemplate.exists(targetDir + "moved.txt")).isTrue();
        assertThat(sftpTemplate.readString(targetDir + "moved.txt")).isEqualTo("Hello Move");

        // 4. 防御性测试：路径穿越与空路径拦截
        assertThatThrownBy(() -> sftpTemplate.move("/test-move/safe.txt", "/test-move/../../etc/passwd"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> sftpTemplate.move("", "/test-move/safe.txt"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> sftpTemplate.move("/test-move/safe.txt", "   "))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("测试 readString 与 writeString 契约：默认 UTF-8、指定编码、覆盖写与异常拦截")
    void testReadStringAndWriteString() {
        String utf8File = "/test-text/utf8.txt";
        String content = "Hello ateng-boot! 优雅且强大的 Spring Boot 3 中间件底座";

        // 1. 默认 UTF-8 写入与读取
        sftpTemplate.writeString(utf8File, content);
        assertThat(sftpTemplate.exists(utf8File)).isTrue();
        assertThat(sftpTemplate.readString(utf8File)).isEqualTo(content);

        // 2. 覆盖写入
        sftpTemplate.writeString(utf8File, "New overwritten content");
        assertThat(sftpTemplate.readString(utf8File)).isEqualTo("New overwritten content");

        // 3. 指定 Charset 编码写入与读取 (GBK)
        Charset gbkCharset = Charset.forName("GBK");
        String gbkFile = "/test-text/gbk.txt";
        String gbkContent = "GBK 编码中文字符串测试";
        sftpTemplate.writeString(gbkFile, gbkContent, gbkCharset);
        assertThat(sftpTemplate.readString(gbkFile, gbkCharset)).isEqualTo(gbkContent);

        // 4. 防御性测试：null 文本快速失败
        assertThatThrownBy(() -> sftpTemplate.writeString(utf8File, null))
                .isInstanceOf(IllegalArgumentException.class);

        // 5. 防御性测试：读取不存在的文件抛出受控业务异常
        assertThatThrownBy(() -> sftpTemplate.readString("/non-existent/file.txt"))
                .isInstanceOf(SshOperationException.class)
                .extracting("resultCode")
                .isEqualTo(SshResultCode.SFTP_FILE_NOT_FOUND);
    }
}
