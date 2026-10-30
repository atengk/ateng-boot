package io.github.atengk.boot.sample.ftp;

import io.github.atengk.boot.ftp.context.FtpContextHolder;
import io.github.atengk.boot.ftp.model.BatchTransferResult;
import io.github.atengk.boot.ftp.model.FtpFileInfo;
import io.github.atengk.boot.sample.ftp.service.ConnectivityProbeScenarioService;
import io.github.atengk.boot.sample.ftp.service.DocumentSyncScenarioService;
import io.github.atengk.boot.sample.ftp.service.DynamicRoutingScenarioService;
import io.github.atengk.boot.sample.ftp.service.LargeFileTransferScenarioService;
import io.github.atengk.boot.sample.ftp.service.SettlementBatchSyncScenarioService;
import lombok.extern.slf4j.Slf4j;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockftpserver.fake.FakeFtpServer;
import org.mockftpserver.fake.UserAccount;
import org.mockftpserver.fake.filesystem.DirectoryEntry;
import org.mockftpserver.fake.filesystem.FileSystem;
import org.mockftpserver.fake.filesystem.UnixFakeFileSystem;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Profile;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

/**
 * FTP 样例工程 5 大真实业务场景端到端集成实测
 * <p>
 * 验证在真实 Spring Boot 容器环境下：
 * 1. DocumentSyncScenarioService: 日常文件读写、流式上传下载、存在探测、元数据查看、增量追加、受管原子复制与安全删除
 * 2. SettlementBatchSyncScenarioService: 金融日结对账全目录递归上传与下载同步、隐藏文件过滤与 Fail-Safe 容错聚合回执
 * 3. LargeFileTransferScenarioService: 大文件流式上传下载与 FtpProgressListener 平滑防抖进度监听
 * 4. DynamicRoutingScenarioService: 多节点动态切源 FtpContextHolder.runWith 栈式隔离与主备互斥
 * 5. ConnectivityProbeScenarioService: 物理连通性自验探针 testConnection() 与 testConnectionOrThrow()
 * </p>
 *
 * @author Ateng
 * @since 2026-10-30
 */
@SpringBootTest
@Slf4j
@DisplayName("FTP 样例工程 5 大真实业务场景端到端集成实测")
public class SampleFtpIntegrationTest {

    private static FakeFtpServer defaultServer;
    private static FakeFtpServer backupServer;

    @Autowired
    private DocumentSyncScenarioService documentSyncService;

    @Autowired
    private SettlementBatchSyncScenarioService settlementBatchSyncService;

    @Autowired
    private LargeFileTransferScenarioService largeFileTransferService;

    @Autowired
    private DynamicRoutingScenarioService dynamicRoutingService;

    @Autowired
    private ConnectivityProbeScenarioService connectivityProbeService;

    private static boolean isLocalProfile() {
        String active = System.getProperty("spring.profiles.active");
        if (active != null && active.contains("local")) {
            return true;
        }
        String envActive = System.getenv("SPRING_PROFILES_ACTIVE");
        return envActive != null && envActive.contains("local");
    }

    static {
        if (!isLocalProfile()) {
            // 1. 启动默认主节点 (动态可用端口)
            defaultServer = new FakeFtpServer();
            defaultServer.setServerControlPort(0);
            FileSystem defaultFs = new UnixFakeFileSystem();
            defaultFs.add(new DirectoryEntry("/"));
            defaultServer.setFileSystem(defaultFs);
            defaultServer.addUserAccount(new UserAccount("admin", "password123", "/"));
            defaultServer.start();

            // 2. 启动备用数据源节点 (动态可用端口)
            backupServer = new FakeFtpServer();
            backupServer.setServerControlPort(0);
            FileSystem backupFs = new UnixFakeFileSystem();
            backupFs.add(new DirectoryEntry("/"));
            backupServer.setFileSystem(backupFs);
            backupServer.addUserAccount(new UserAccount("backup_user", "backup_password", "/"));
            backupServer.start();
        }
    }

    @DynamicPropertySource
    static void configureFtpProperties(DynamicPropertyRegistry registry) {
        if (!isLocalProfile() && defaultServer != null && backupServer != null) {
            registry.add("ateng.boot.ftp.port", () -> defaultServer.getServerControlPort());
            registry.add("ateng.boot.ftp.servers.backup.port", () -> backupServer.getServerControlPort());
        }
    }

    @AfterAll
    static void stopServers() {
        if (defaultServer != null) {
            defaultServer.stop();
        }
        if (backupServer != null) {
            backupServer.stop();
        }
    }

    @AfterEach
    void tearDown() {
        FtpContextHolder.clear();
    }

    @TestConfiguration
    @Profile("!local")
    static class OfflineFtpTestConfig {
        // 标配 @Profile("!local")，当激活 local Profile 联调真实环境时自动失效
    }

    @Test
    @DisplayName("01. DocumentSyncScenarioService: 企业日常文档全生命周期自验")
    void testDocumentSyncScenario(@TempDir Path tempDir) throws IOException {
        String remoteDocPath = "/docs/policy.txt";
        String initialContent = "Enterprise Security Policy v1.0";

        // 1. 纯文本快捷写入与读取
        boolean written = documentSyncService.writeText(remoteDocPath, initialContent);
        assertThat(written).isTrue();
        assertThat(documentSyncService.exists(remoteDocPath)).isTrue();
        assertThat(documentSyncService.readText(remoteDocPath)).isEqualTo(initialContent);

        // 2. 增量追加写入 (RFC 959 APPE)
        boolean appended = documentSyncService.appendText(remoteDocPath, "\n[Append] Approved by Security Committee");
        assertThat(appended).isTrue();
        String updatedContent = documentSyncService.readText(remoteDocPath);
        assertThat(updatedContent).contains("Approved by Security Committee");

        // 3. 元数据解析
        FtpFileInfo info = documentSyncService.getDocumentInfo(remoteDocPath);
        assertThat(info).isNotNull();
        assertThat(info.getName()).isEqualTo("policy.txt");
        assertThat(info.getSize()).isPositive();
        assertThat(info.isDirectory()).isFalse();
        assertThat(info.isFile()).isTrue();

        // 4. 受管单文件管道原子复制 (copy)
        String copiedPath = "/docs/policy_backup.txt";
        boolean copied = documentSyncService.copyDocument(remoteDocPath, copiedPath);
        assertThat(copied).isTrue();
        assertThat(documentSyncService.exists(copiedPath)).isTrue();
        assertThat(documentSyncService.readText(copiedPath)).isEqualTo(updatedContent);

        // 5. 重命名与跨目录移动
        String renamedPath = "/docs/policy_v2.txt";
        boolean renamed = documentSyncService.renameDocument(copiedPath, renamedPath);
        assertThat(renamed).isTrue();
        assertThat(documentSyncService.exists(copiedPath)).isFalse();
        assertThat(documentSyncService.exists(renamedPath)).isTrue();

        boolean moved = documentSyncService.moveDocument(renamedPath, "/archive");
        assertThat(moved).isTrue();
        assertThat(documentSyncService.exists(renamedPath)).isFalse();
        assertThat(documentSyncService.exists("/archive/policy_v2.txt")).isTrue();

        // 6. 字节上传与本地磁盘下载
        byte[] rawBytes = "Raw Binary Data 2026".getBytes(StandardCharsets.UTF_8);
        documentSyncService.uploadBytes("/docs/raw.bin", rawBytes);
        Path localDownloadFile = tempDir.resolve("downloaded_raw.bin");
        documentSyncService.downloadToFile("/docs/raw.bin", localDownloadFile.toFile());
        assertThat(Files.readAllBytes(localDownloadFile)).isEqualTo(rawBytes);

        // 7. 目录枚举与清理
        List<FtpFileInfo> docList = documentSyncService.listDocuments("/docs");
        assertThat(docList).extracting(FtpFileInfo::getName).contains("policy.txt", "raw.bin");

        documentSyncService.deleteDocument(remoteDocPath);
        documentSyncService.deleteDocument("/docs/raw.bin");
        documentSyncService.deleteDirectory("/archive");
    }

    @Test
    @DisplayName("02. SettlementBatchSyncScenarioService: 金融日结对账全目录递归上传与下载自验")
    void testSettlementBatchSyncScenario(@TempDir Path tempDir) throws IOException {
        Path localSettlementDir = tempDir.resolve("settlement_source");
        Files.createDirectories(localSettlementDir.resolve("20261030/sub_batch"));
        Files.writeString(localSettlementDir.resolve("20261030/summary.csv"), "batch_id,total\nB001,10000.00");
        Files.writeString(localSettlementDir.resolve("20261030/sub_batch/detail.csv"), "item_id,price\nITM01,50.00");

        // 注入操作系统隐性文件 (验证自动剔除)
        Files.writeString(localSettlementDir.resolve("desktop.ini"), "icon_cache");
        Files.writeString(localSettlementDir.resolve("20261030/.DS_Store"), "mac_metadata");
        Files.writeString(localSettlementDir.resolve("20261030/Thumbs.db"), "thumb_cache");
        Files.writeString(localSettlementDir.resolve("20261030/temp.tmp"), "temp_data");

        // 1. 批量递归同步至远端 FTP
        String remoteSettlementDir = "/settlement/batch";
        BatchTransferResult uploadResult = settlementBatchSyncService.syncSettlementToRemote(
                localSettlementDir.toFile(), remoteSettlementDir);

        assertThat(uploadResult.isAllSuccess()).isTrue();
        assertThat(uploadResult.hasFailure()).isFalse();
        assertThat(uploadResult.getSuccessCount()).isEqualTo(2);
        assertThat(uploadResult.getSkippedCount()).isGreaterThanOrEqualTo(4);
        assertThat(uploadResult.getTransferredBytes()).isPositive();
        assertThat(uploadResult.getDuration()).isNotNull();

        assertThat(documentSyncService.exists("/settlement/batch/20261030/summary.csv")).isTrue();
        assertThat(documentSyncService.exists("/settlement/batch/20261030/sub_batch/detail.csv")).isTrue();
        assertThat(documentSyncService.exists("/settlement/batch/desktop.ini")).isFalse();
        assertThat(documentSyncService.exists("/settlement/batch/20261030/.DS_Store")).isFalse();

        // 2. 批量递归下载回本地工作区
        Path localAuditDir = tempDir.resolve("settlement_audit");
        BatchTransferResult downloadResult = settlementBatchSyncService.syncSettlementFromRemote(
                remoteSettlementDir, localAuditDir.toFile());

        assertThat(downloadResult.isAllSuccess()).isTrue();
        assertThat(downloadResult.getSuccessCount()).isEqualTo(2);
        assertThat(Files.readString(localAuditDir.resolve("20261030/summary.csv"))).isEqualTo("batch_id,total\nB001,10000.00");
        assertThat(Files.readString(localAuditDir.resolve("20261030/sub_batch/detail.csv"))).isEqualTo("item_id,price\nITM01,50.00");

        // 3. 清理远端结算测试数据
        documentSyncService.deleteDirectory("/settlement");
    }

    @Test
    @DisplayName("03. LargeFileTransferScenarioService: 大文件流式传输与平滑进度监听自验")
    void testLargeFileTransferScenario(@TempDir Path tempDir) throws IOException {
        Path localLargeFile = tempDir.resolve("sample_large_file.dat");
        byte[] payload = new byte[64 * 1024]; // 64KB 数据载荷
        for (int i = 0; i < payload.length; i++) {
            payload[i] = (byte) (i % 128);
        }
        Files.write(localLargeFile, payload);

        // 1. 上传大文件并挂载进度监听器
        AtomicReference<Double> uploadProgress = new AtomicReference<>(0.0);
        AtomicReference<Long> uploadBytes = new AtomicReference<>(0L);
        boolean uploaded = largeFileTransferService.uploadLargeFile("/large/payload.dat", localLargeFile.toFile(),
                (bytesTransferred, totalBytes, percentage) -> {
                    uploadBytes.set(bytesTransferred);
                    uploadProgress.set(percentage);
                });

        assertThat(uploaded).isTrue();
        assertThat(uploadBytes.get()).isEqualTo((long) payload.length);
        assertThat(uploadProgress.get()).isEqualTo(100.0);

        // 2. 下载大文件并挂载进度监听器
        Path downloadedFile = tempDir.resolve("downloaded_large_file.dat");
        AtomicReference<Double> downloadProgress = new AtomicReference<>(0.0);
        AtomicReference<Long> downloadBytes = new AtomicReference<>(0L);
        boolean downloaded = largeFileTransferService.downloadLargeFile("/large/payload.dat", downloadedFile.toFile(),
                (bytesTransferred, totalBytes, percentage) -> {
                    downloadBytes.set(bytesTransferred);
                    downloadProgress.set(percentage);
                });

        assertThat(downloaded).isTrue();
        assertThat(downloadBytes.get()).isEqualTo((long) payload.length);
        assertThat(downloadProgress.get()).isEqualTo(100.0);
        assertThat(Files.readAllBytes(downloadedFile)).isEqualTo(payload);

        // 3. 清理远端测试文件
        documentSyncService.deleteDirectory("/large");
    }

    @Test
    @DisplayName("04. DynamicRoutingScenarioService: 多节点动态切源与隔离路由自验")
    void testDynamicRoutingScenario() {
        String backupDocPath = "/backup_data/audit_log.txt";
        String backupContent = "Audit Log Recorded in Backup Server 2026";

        // 1. 写入备用节点 (backup)
        boolean written = dynamicRoutingService.writeToBackupServer(backupDocPath, backupContent);
        assertThat(written).isTrue();

        // 2. 从备用节点读取内容
        String readBack = dynamicRoutingService.readFromBackupServer(backupDocPath);
        assertThat(readBack).isEqualTo(backupContent);
        assertThat(dynamicRoutingService.existsOnBackupServer(backupDocPath)).isTrue();

        // 3. 验证主节点隔离性：离线模式下主备节点为不同实例，验证隔离；local 模式共用单实例时跳过
        if (!isLocalProfile()) {
            assertThat(documentSyncService.exists(backupDocPath)).isFalse();
        }

        // 4. 清理备用节点测试数据
        dynamicRoutingService.executeOnServer("backup", () -> documentSyncService.deleteDirectory("/backup_data"));
    }

    @Test
    @DisplayName("05. ConnectivityProbeScenarioService: 物理连通性自验探针自验")
    void testConnectivityProbeScenario() {
        // 1. 探测默认主节点连通性
        boolean defaultHealthy = connectivityProbeService.checkDefaultServerHealth();
        assertThat(defaultHealthy).isTrue();

        // 2. 探测备用节点连通性
        boolean backupHealthy = connectivityProbeService.checkServerHealth("backup");
        assertThat(backupHealthy).isTrue();

        // 3. 强断言连通性不抛出异常
        assertThatCode(() -> connectivityProbeService.assertServerHealthy("backup"))
                .doesNotThrowAnyException();
    }
}
