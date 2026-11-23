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
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * SftpTemplate 具名节点路由重载、符号链接治理与大文件断点续传下载单元测试
 *
 * @author Ateng
 * @since 2026-11-17
 */
@DisplayName("SftpTemplate 具名节点路由、符号链接与断点续传单元测试")
class SftpTemplateNamedAndAdvancedTest {

    private static final String BACKUP_SERVER = "backup";

    private SshServer defaultServer;
    private SshServer backupServer;
    private SshClient sshClient;
    private SshSessionPool sessionPool;
    private SshClientRegistry clientRegistry;
    private SshProperties sshProperties;
    private SftpTemplate sftpTemplate;
    private Path defaultRoot;
    private Path backupRoot;
    private int backupPort;

    @BeforeEach
    void setUp(@TempDir Path tempDir) throws IOException {
        defaultRoot = tempDir.resolve("sftp-default");
        backupRoot = tempDir.resolve("sftp-backup");
        Files.createDirectories(defaultRoot);
        Files.createDirectories(backupRoot);

        defaultServer = startSftpServer(tempDir.resolve("default_key.ser"), "defaultuser", "defaultpass", defaultRoot);
        backupServer = startSftpServer(tempDir.resolve("backup_key.ser"), "backupuser", "backuppass", backupRoot);
        backupPort = backupServer.getPort();

        sshClient = SshClient.setUpDefaultClient();
        sshClient.setServerKeyVerifier(AcceptAllServerKeyVerifier.INSTANCE);
        sshClient.start();

        sshProperties = new SshProperties();
        sshProperties.setHost("127.0.0.1");
        sshProperties.setPort(defaultServer.getPort());
        sshProperties.setUsername("defaultuser");
        sshProperties.setPassword("defaultpass");

        SshPoolProperties poolProps = new SshPoolProperties();
        poolProps.setMaxSessions(10);
        sessionPool = new SshSessionPool(sshClient, poolProps);

        clientRegistry = new SshClientRegistry();
        clientRegistry.register(SshClientRegistry.DEFAULT_SERVER, SshServerInfo.fromProperties(sshProperties));
        clientRegistry.register(BACKUP_SERVER, createBackupServerInfo());

        sftpTemplate = new SftpTemplate(sessionPool, clientRegistry, sshProperties);
    }

    private SshServerInfo createBackupServerInfo() {
        return SshServerInfo.builder()
                .host("127.0.0.1")
                .port(backupPort)
                .username("backupuser")
                .password("backuppass")
                .build();
    }

    private SshServer startSftpServer(Path hostKeyPath, String username, String password, Path root) throws IOException {
        SshServer server = SshServer.setUpDefaultServer();
        server.setHost("127.0.0.1");
        server.setPort(0);
        server.setKeyPairProvider(new SimpleGeneratorHostKeyProvider(hostKeyPath));
        server.setPasswordAuthenticator((u, p, s) -> username.equals(u) && password.equals(p));
        server.setSubsystemFactories(Collections.singletonList(new SftpSubsystemFactory()));
        server.setFileSystemFactory(new VirtualFileSystemFactory(root));
        server.start();
        return server;
    }

    @AfterEach
    void tearDown() {
        if (sessionPool != null) {
            sessionPool.close();
        }
        if (sshClient != null) {
            sshClient.stop();
        }
        stopServerQuietly(defaultServer);
        stopServerQuietly(backupServer);
    }

    private void stopServerQuietly(SshServer server) {
        if (server != null) {
            try {
                server.stop(true);
            } catch (IOException ignored) {
            }
        }
    }

    @Test
    @DisplayName("测试全量方法支持 serverName 具名节点路由并与 default 节点物理隔离")
    void testNamedServerRoutingAcrossAllMethods(@TempDir Path localTemp) throws IOException {
        // 1. upload(serverName, byte[]) & upload(serverName, byte[], listener) & exists/stat/downloadToBytes
        byte[] payload = "backup-node-payload".getBytes(StandardCharsets.UTF_8);
        AtomicLong uploadedBytes = new AtomicLong(0);
        sftpTemplate.upload(BACKUP_SERVER, "/named/bytes.txt", payload, (transferred, total) -> uploadedBytes.set(transferred));
        assertThat(uploadedBytes.get()).isEqualTo(payload.length);
        assertThat(sftpTemplate.exists(BACKUP_SERVER, "/named/bytes.txt")).isTrue();
        assertThat(sftpTemplate.exists("/named/bytes.txt")).isFalse();
        assertThat(sftpTemplate.downloadToBytes(BACKUP_SERVER, "/named/bytes.txt")).isEqualTo(payload);
        SftpFileInfo info = sftpTemplate.stat(BACKUP_SERVER, "/named/bytes.txt");
        assertThat(info.getSize()).isEqualTo(payload.length);

        // 2. upload(serverName, InputStream) & upload(serverName, InputStream, listener)
        byte[] streamData = "stream-to-backup".getBytes(StandardCharsets.UTF_8);
        sftpTemplate.upload(BACKUP_SERVER, "/named/stream1.txt", new ByteArrayInputStream(streamData));
        AtomicBoolean streamUploaded = new AtomicBoolean(false);
        sftpTemplate.upload(BACKUP_SERVER, "/named/stream2.txt", new ByteArrayInputStream(streamData),
                (t, total) -> streamUploaded.set(true));
        assertThat(streamUploaded.get()).isTrue();

        // 3. upload(serverName, File) & upload(serverName, File, listener)
        Path localSrc = localTemp.resolve("local-upload.txt");
        Files.writeString(localSrc, "file-to-backup");
        sftpTemplate.upload(BACKUP_SERVER, "/named/file1.txt", localSrc.toFile());
        AtomicLong fileUploadTotal = new AtomicLong(0);
        sftpTemplate.upload(BACKUP_SERVER, "/named/file2.txt", localSrc.toFile(),
                (t, total) -> fileUploadTotal.set(total));
        assertThat(fileUploadTotal.get()).isEqualTo(Files.size(localSrc));

        // 4. download(serverName, OutputStream, listener) & download(serverName, Consumer<InputStream>)
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        AtomicLong downloadedBytes = new AtomicLong(0);
        sftpTemplate.download(BACKUP_SERVER, "/named/file1.txt", baos, (t, total) -> downloadedBytes.set(t));
        assertThat(baos.toString(StandardCharsets.UTF_8)).isEqualTo("file-to-backup");
        assertThat(downloadedBytes.get()).isEqualTo(Files.size(localSrc));

        ByteArrayOutputStream baos2 = new ByteArrayOutputStream();
        sftpTemplate.download(BACKUP_SERVER, "/named/file2.txt", baos2);
        assertThat(baos2.toString(StandardCharsets.UTF_8)).isEqualTo("file-to-backup");

        AtomicBoolean consumed = new AtomicBoolean(false);
        sftpTemplate.download(BACKUP_SERVER, "/named/stream1.txt", in -> {
            try {
                assertThat(new String(in.readAllBytes(), StandardCharsets.UTF_8)).isEqualTo("stream-to-backup");
                consumed.set(true);
            } catch (IOException e) {
                throw new RuntimeException(e);
            }
        });
        assertThat(consumed.get()).isTrue();

        // 5. downloadToFile(serverName, ...)
        Path dest1 = localTemp.resolve("dest1.txt");
        Path dest2 = localTemp.resolve("dest2.txt");
        sftpTemplate.downloadToFile(BACKUP_SERVER, "/named/file1.txt", dest1.toFile());
        AtomicLong dest2Bytes = new AtomicLong(0);
        sftpTemplate.downloadToFile(BACKUP_SERVER, "/named/file2.txt", dest2.toFile(), (t, total) -> dest2Bytes.set(t));
        assertThat(Files.readString(dest1)).isEqualTo("file-to-backup");
        assertThat(Files.readString(dest2)).isEqualTo("file-to-backup");
        assertThat(dest2Bytes.get()).isEqualTo(Files.size(localSrc));

        // 6. mkdir / mkdirs / chmod / list / delete
        sftpTemplate.mkdir(BACKUP_SERVER, "/single-dir");
        sftpTemplate.mkdirs(BACKUP_SERVER, "/multi/sub");
        assertThat(sftpTemplate.exists(BACKUP_SERVER, "/multi/sub")).isTrue();

        sftpTemplate.chmod(BACKUP_SERVER, "/named/file1.txt", 0755);
        List<SftpFileInfo> entries = sftpTemplate.list(BACKUP_SERVER, "/named");
        assertThat(entries).extracting(SftpFileInfo::getFilename)
                .containsExactlyInAnyOrder("bytes.txt", "stream1.txt", "stream2.txt", "file1.txt", "file2.txt");

        assertThat(sftpTemplate.delete(BACKUP_SERVER, "/named/stream2.txt")).isTrue();
        assertThat(sftpTemplate.exists(BACKUP_SERVER, "/named/stream2.txt")).isFalse();

        // 7. uploadDirectory / downloadDirectory / deleteRecursively
        Path syncSrc = localTemp.resolve("sync-src");
        Files.createDirectories(syncSrc.resolve("nested"));
        Files.writeString(syncSrc.resolve("nested/item.txt"), "sync-item");

        sftpTemplate.uploadDirectory(BACKUP_SERVER, syncSrc, "/named-sync-path");
        sftpTemplate.uploadDirectory(BACKUP_SERVER, syncSrc.toFile(), "/named-sync-file");
        assertThat(sftpTemplate.exists(BACKUP_SERVER, "/named-sync-path/nested/item.txt")).isTrue();
        assertThat(sftpTemplate.exists(BACKUP_SERVER, "/named-sync-file/nested/item.txt")).isTrue();
        assertThat(sftpTemplate.exists("/named-sync-path/nested/item.txt")).isFalse();

        Path syncDest1 = localTemp.resolve("sync-dest-1");
        Path syncDest2 = localTemp.resolve("sync-dest-2");
        sftpTemplate.downloadDirectory(BACKUP_SERVER, "/named-sync-path", syncDest1);
        sftpTemplate.downloadDirectory(BACKUP_SERVER, "/named-sync-file", syncDest2.toFile());
        assertThat(Files.readString(syncDest1.resolve("nested/item.txt"))).isEqualTo("sync-item");
        assertThat(Files.readString(syncDest2.resolve("nested/item.txt"))).isEqualTo("sync-item");

        assertThat(sftpTemplate.deleteRecursively(BACKUP_SERVER, "/named-sync-path")).isTrue();
        assertThat(sftpTemplate.exists(BACKUP_SERVER, "/named-sync-path")).isFalse();
    }

    @Test
    @DisplayName("测试 createSymlink 与 readLink：默认路由、具名节点、动态描述符与路径穿越拦截")
    void testCreateSymlinkAndReadLink() {
        // 1. 默认节点创建并读取符号链接
        String targetPath = "/releases/v1.0/app.jar";
        String linkPath = "/releases/current/app.jar";
        sftpTemplate.writeString(targetPath, "release-v1-binary");

        sftpTemplate.createSymlink(linkPath, targetPath);
        String resolvedTarget = sftpTemplate.readLink(linkPath);
        assertThat(resolvedTarget.replace('\\', '/')).contains("releases/v1.0/app.jar");
        assertThat(sftpTemplate.readString(linkPath)).isEqualTo("release-v1-binary");

        // 2. 具名节点重载
        String backupTarget = "/backup-releases/v2/app.jar";
        String backupLink = "/backup-releases/latest";
        sftpTemplate.writeString(BACKUP_SERVER, backupTarget, "release-v2-binary");
        sftpTemplate.createSymlink(BACKUP_SERVER, backupLink, backupTarget);
        assertThat(sftpTemplate.readLink(BACKUP_SERVER, backupLink).replace('\\', '/'))
                .contains("backup-releases/v2/app.jar");
        assertThat(sftpTemplate.exists(backupLink)).isFalse();

        // 3. 动态 SshServerInfo 重载
        SshServerInfo backupInfo = createBackupServerInfo();
        String dynamicLink = "/backup-releases/dynamic-link";
        sftpTemplate.createSymlink(backupInfo, dynamicLink, backupTarget);
        assertThat(sftpTemplate.readLink(backupInfo, dynamicLink).replace('\\', '/'))
                .contains("backup-releases/v2/app.jar");

        // 4. 路径穿越拦截
        assertThatThrownBy(() -> sftpTemplate.createSymlink("/sub/../../evil-link", targetPath))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Path Traversal");
        assertThatThrownBy(() -> sftpTemplate.createSymlink("/releases/safe-link", "/sub/../../etc/passwd"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Path Traversal");
        assertThatThrownBy(() -> sftpTemplate.readLink("../outside-link"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Path Traversal");

        // 5. 读取不存在的符号链接与普通文件语义异常
        assertThatThrownBy(() -> sftpTemplate.readLink("/releases/non-existent-link"))
                .isInstanceOf(SshOperationException.class)
                .extracting("resultCode")
                .isEqualTo(SshResultCode.SFTP_FILE_NOT_FOUND);

        assertThatThrownBy(() -> sftpTemplate.readLink(targetPath))
                .isInstanceOf(SshOperationException.class);
    }

    @Test
    @DisplayName("测试 downloadResume：断点续传精确拼接、满额幂等、零字节、超长异常阻断与具名路由")
    void testDownloadResume(@TempDir Path localTemp) throws IOException {
        byte[] payload = new byte[100 * 1024];
        for (int i = 0; i < payload.length; i++) {
            payload[i] = (byte) ((i * 31 + 7) & 0xFF);
        }
        String remotePath = "/artifacts/big-bundle.bin";
        sftpTemplate.upload(remotePath, payload);

        // 1. 半截本地文件 (前 40%) 精确续传至 100% 一致
        int partialLen = (int) (payload.length * 0.4);
        Path partialFile = localTemp.resolve("sub/big-bundle.bin");
        Files.createDirectories(partialFile.getParent());
        byte[] partialBytes = new byte[partialLen];
        System.arraycopy(payload, 0, partialBytes, 0, partialLen);
        Files.write(partialFile, partialBytes);

        AtomicLong firstCallback = new AtomicLong(-1);
        AtomicLong lastTransferred = new AtomicLong(-1);
        AtomicLong lastTotal = new AtomicLong(-1);
        AtomicBoolean listenerClosed = new AtomicBoolean(false);
        class CloseableProgressListener implements SftpProgressListener, AutoCloseable {
            @Override
            public void onProgress(long transferredBytes, long totalBytes) {
                firstCallback.compareAndSet(-1, transferredBytes);
                lastTransferred.set(transferredBytes);
                lastTotal.set(totalBytes);
            }

            @Override
            public void close() {
                listenerClosed.set(true);
            }
        }

        sftpTemplate.downloadResume(remotePath, partialFile.toFile(), new CloseableProgressListener());
        assertThat(Files.readAllBytes(partialFile)).isEqualTo(payload);
        assertThat(firstCallback.get()).isGreaterThan(partialLen);
        assertThat(lastTransferred.get()).isEqualTo(payload.length);
        assertThat(lastTotal.get()).isEqualTo(payload.length);
        assertThat(listenerClosed.get()).isTrue();

        // 2. 已完整下载的文件再次调用应幂等返回并触发 100% 满额回调
        AtomicLong idempotentTransferred = new AtomicLong(-1);
        AtomicLong idempotentTotal = new AtomicLong(-1);
        sftpTemplate.downloadResume(remotePath, partialFile.toFile(), (transferred, total) -> {
            idempotentTransferred.set(transferred);
            idempotentTotal.set(total);
        });
        assertThat(idempotentTransferred.get()).isEqualTo(payload.length);
        assertThat(idempotentTotal.get()).isEqualTo(payload.length);
        assertThat(Files.readAllBytes(partialFile)).isEqualTo(payload);

        // 3. 本地文件不存在时从头完整下载
        Path freshFile = localTemp.resolve("fresh/bundle.bin");
        sftpTemplate.downloadResume(remotePath, freshFile.toFile());
        assertThat(Files.readAllBytes(freshFile)).isEqualTo(payload);

        // 4. 远程 0 字节文件在本地不存在时创建空文件并触发 (0, 0) 回调
        String emptyRemote = "/artifacts/empty.bin";
        sftpTemplate.upload(emptyRemote, new byte[0]);
        Path emptyLocal = localTemp.resolve("empty.bin");
        AtomicLong emptyTransferred = new AtomicLong(-1);
        sftpTemplate.downloadResume(emptyRemote, emptyLocal.toFile(), (t, total) -> emptyTransferred.set(t));
        assertThat(Files.exists(emptyLocal)).isTrue();
        assertThat(Files.size(emptyLocal)).isZero();
        assertThat(emptyTransferred.get()).isZero();

        // 5. 本地文件大小大于远程文件时立即 Fail-Fast 抛出 SFTP_TRANSFER_FAILED 且不篡改本地文件
        Path oversizedFile = localTemp.resolve("oversized.bin");
        byte[] oversizedData = new byte[payload.length + 128];
        Files.write(oversizedFile, oversizedData);
        assertThatThrownBy(() -> sftpTemplate.downloadResume(remotePath, oversizedFile.toFile()))
                .isInstanceOf(SshOperationException.class)
                .extracting("resultCode")
                .isEqualTo(SshResultCode.SFTP_TRANSFER_FAILED);
        assertThat(Files.size(oversizedFile)).isEqualTo(oversizedData.length);

        // 6. 具名节点与动态 SshServerInfo 重载
        String backupRemote = "/backup-artifacts/bundle.bin";
        sftpTemplate.upload(BACKUP_SERVER, backupRemote, payload);
        Path backupLocal = localTemp.resolve("backup-bundle.bin");
        byte[] backupPartial = new byte[1024];
        System.arraycopy(payload, 0, backupPartial, 0, 1024);
        Files.write(backupLocal, backupPartial);
        sftpTemplate.downloadResume(BACKUP_SERVER, backupRemote, backupLocal.toFile());
        assertThat(Files.readAllBytes(backupLocal)).isEqualTo(payload);

        SshServerInfo backupInfo = createBackupServerInfo();
        Path dynamicLocal = localTemp.resolve("dynamic-bundle.bin");
        sftpTemplate.downloadResume(backupInfo, backupRemote, dynamicLocal.toFile());
        assertThat(Files.readAllBytes(dynamicLocal)).isEqualTo(payload);

        // 7. 边界防御：远程文件不存在、远程为目录、本地为目录、路径穿越
        assertThatThrownBy(() -> sftpTemplate.downloadResume("/artifacts/not-found.bin", localTemp.resolve("nf.bin").toFile()))
                .isInstanceOf(SshOperationException.class)
                .extracting("resultCode")
                .isEqualTo(SshResultCode.SFTP_FILE_NOT_FOUND);

        sftpTemplate.mkdirs("/artifacts/dir");
        assertThatThrownBy(() -> sftpTemplate.downloadResume("/artifacts/dir", localTemp.resolve("dir.bin").toFile()))
                .isInstanceOf(SshOperationException.class)
                .extracting("resultCode")
                .isEqualTo(SshResultCode.SFTP_TRANSFER_FAILED);

        assertThatThrownBy(() -> sftpTemplate.downloadResume(remotePath, localTemp.toFile()))
                .isInstanceOf(IllegalArgumentException.class);

        assertThatThrownBy(() -> sftpTemplate.downloadResume("../secret.bin", localTemp.resolve("s.bin").toFile()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Path Traversal");
    }
}
