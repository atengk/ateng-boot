package io.github.atengk.boot.sample.ftp;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.atengk.boot.ftp.context.FtpContextHolder;
import io.github.atengk.boot.sample.ftp.dto.AppendTextRequest;
import io.github.atengk.boot.sample.ftp.dto.CopyMoveRequest;
import io.github.atengk.boot.sample.ftp.dto.DeleteDirectoryRequest;
import io.github.atengk.boot.sample.ftp.dto.DirectorySyncRequest;
import io.github.atengk.boot.sample.ftp.dto.DynamicRoutingRequest;
import io.github.atengk.boot.sample.ftp.dto.LargeFileTransferRequest;
import io.github.atengk.boot.sample.ftp.dto.PathRequest;
import io.github.atengk.boot.sample.ftp.dto.RenameRequest;
import io.github.atengk.boot.sample.ftp.dto.UploadFileRequest;
import io.github.atengk.boot.sample.ftp.dto.WriteTextRequest;
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
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.notNullValue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * FTP 样例工程 4 大领域控制器 Web 端点全真双模回归测试
 * <p>
 * 验证在真实 Spring Boot Web 容器环境下：
 * 1. FtpDocumentController: 表单/本地文件上传、流式下载、文本读写、追加写、管道流原子对拷、重命名、移动、删除与 MLSD 机器解析
 * 2. FtpBatchSyncController: 全目录镜像上传/下载、隐性文件过滤与 Fail-Safe 容错聚合回执
 * 3. FtpTransferController: 大文件流式吞吐、防抖节流进度监听与 50MB 内存防爆
 * 4. FtpGovernanceController: 物理连通性探针（NOOP）、断言检查、栈式动态切源与连接池状态
 * </p>
 *
 * @author Ateng
 * @since 2026-11-10
 */
@SpringBootTest
@AutoConfigureMockMvc
@Slf4j
@DisplayName("FTP 样例工程 4 大领域控制器 Web 端点双模全真回归")
public class SampleFtpWebIntegrationTest {

    private static FakeFtpServer defaultServer;
    private static FakeFtpServer backupServer;

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

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
            registry.add("ateng.boot.ftp.host", () -> "127.0.0.1");
            registry.add("ateng.boot.ftp.port", () -> defaultServer.getServerControlPort());
            registry.add("ateng.boot.ftp.username", () -> "admin");
            registry.add("ateng.boot.ftp.password", () -> "password123");
            registry.add("ateng.boot.ftp.servers.backup.host", () -> "127.0.0.1");
            registry.add("ateng.boot.ftp.servers.backup.port", () -> backupServer.getServerControlPort());
            registry.add("ateng.boot.ftp.servers.backup.username", () -> "backup_user");
            registry.add("ateng.boot.ftp.servers.backup.password", () -> "backup_password");
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

    @Test
    @DisplayName("01. FtpDocumentController: 表单文件上传、文本读写、追加写、下载与生命周期")
    void testDocumentControllerLifecycle(@TempDir Path tempDir) throws Exception {
        String testDocPath = "/web-docs/notice.txt";
        String initialContent = "Company Policy Notice v1.0";

        // 1. 文本快速写入
        WriteTextRequest writeReq = WriteTextRequest.builder()
                .remotePath(testDocPath)
                .content(initialContent)
                .build();
        mockMvc.perform(post("/sample/ftp/document/write-text")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(writeReq)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data").value(true));

        // 2. 文本快速读取
        mockMvc.perform(get("/sample/ftp/document/read-text")
                        .param("remotePath", testDocPath))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data").value(initialContent));

        // 3. RFC 959 增量追加写 (APPE)
        String appendSnippet = "\nAmendment: Section 4.2 Approved.";
        AppendTextRequest appendReq = AppendTextRequest.builder()
                .remotePath(testDocPath)
                .content(appendSnippet)
                .build();
        mockMvc.perform(post("/sample/ftp/document/append-text")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(appendReq)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data").value(true));

        // 4. 读取追加后的完整内容验证
        mockMvc.perform(get("/sample/ftp/document/read-text")
                        .param("remotePath", testDocPath))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data").value(initialContent + appendSnippet));

        // 5. MultipartFile 表单文件上传
        byte[] uploadBytes = "Audit Attachment Content".getBytes(StandardCharsets.UTF_8);
        MockMultipartFile multipartFile = new MockMultipartFile(
                "file", "audit-report.txt", MediaType.TEXT_PLAIN_VALUE, uploadBytes);
        mockMvc.perform(multipart("/sample/ftp/document/upload")
                        .file(multipartFile)
                        .param("remotePath", "/web-docs/audit-report.txt"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data.name").value("audit-report.txt"))
                .andExpect(jsonPath("$.data.file").value(true));

        // 6. 本地磁盘文件上传
        Path localFilePath = tempDir.resolve("local-contract.txt");
        Files.writeString(localFilePath, "Enterprise Contract Signed 2026", StandardCharsets.UTF_8);
        UploadFileRequest uploadFileReq = UploadFileRequest.builder()
                .localPath(localFilePath.toAbsolutePath().toString())
                .remotePath("/web-docs/local-contract.txt")
                .build();
        mockMvc.perform(post("/sample/ftp/document/upload-file")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(uploadFileReq)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data").value(true));

        // 7. 查询文件元数据
        mockMvc.perform(get("/sample/ftp/document/info")
                        .param("remotePath", "/web-docs/local-contract.txt"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.name").value("local-contract.txt"))
                .andExpect(jsonPath("$.data.size").value(Files.size(localFilePath)));

        // 8. RFC 3659 MLSD 目录列表查看
        mockMvc.perform(get("/sample/ftp/document/list-files")
                        .param("remoteDir", "/web-docs"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data").isArray());

        // 9. 受管管道流原子对拷 (copy)
        CopyMoveRequest copyReq = CopyMoveRequest.builder()
                .sourcePath("/web-docs/local-contract.txt")
                .targetPath("/web-docs/local-contract-bak.txt")
                .build();
        mockMvc.perform(post("/sample/ftp/document/copy")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(copyReq)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data").value(true));

        // 10. 文件重命名 (rename)
        RenameRequest renameReq = RenameRequest.builder()
                .sourcePath("/web-docs/local-contract-bak.txt")
                .targetPath("/web-docs/local-contract-renamed.txt")
                .build();
        mockMvc.perform(post("/sample/ftp/document/rename")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(renameReq)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data").value(true));

        // 11. 跨目录移动 (move)
        CopyMoveRequest moveReq = CopyMoveRequest.builder()
                .sourcePath("/web-docs/local-contract-renamed.txt")
                .targetPath("/web-archive")
                .build();
        mockMvc.perform(post("/sample/ftp/document/move")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(moveReq)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data").value(true));

        // 12. 流式下载验证
        mockMvc.perform(get("/sample/ftp/document/download")
                        .param("remotePath", "/web-docs/notice.txt"))
                .andExpect(status().isOk())
                .andExpect(header().string(HttpHeaders.CONTENT_DISPOSITION, containsString("notice.txt")))
                .andExpect(result -> assertThat(result.getResponse().getContentAsString()).contains(initialContent));

        // 13. 单文件删除 (delete-file)
        PathRequest delFileReq = PathRequest.builder().path("/web-docs/audit-report.txt").build();
        mockMvc.perform(post("/sample/ftp/document/delete-file")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(delFileReq)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data").value(true));

        // 14. 目录级联删除 (delete-directory)
        DeleteDirectoryRequest delDirReq = DeleteDirectoryRequest.builder().path("/web-archive").recursive(true).build();
        mockMvc.perform(post("/sample/ftp/document/delete-directory")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(delDirReq)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data").value(true));
    }

    @Test
    @DisplayName("02. FtpBatchSyncController: 全目录镜像同步与 Fail-Safe 容错批处理")
    void testBatchSyncController(@TempDir Path tempDir) throws Exception {
        // 本地准备多层级金融结算账单目录树
        Path localSettlementRoot = tempDir.resolve("settlement_root");
        Path localSubDir = localSettlementRoot.resolve("batch_20261110");
        Files.createDirectories(localSubDir);

        Files.writeString(localSettlementRoot.resolve("summary.csv"), "ID,Amount,Status\n1,100,SUCCESS\n");
        Files.writeString(localSubDir.resolve("detail_01.csv"), "ITEM,Qty\nPhone,2\n");
        Files.writeString(localSubDir.resolve("desktop.ini"), "[.ShellClassInfo]\nIconIndex=0\n"); // 宿主隐性文件，应自动过滤

        // 1. 批量上传目录
        DirectorySyncRequest uploadReq = DirectorySyncRequest.builder()
                .localDir(localSettlementRoot.toAbsolutePath().toString())
                .remoteDir("/finance/settlement")
                .filterExtension(".csv")
                .failFast(false)
                .build();

        mockMvc.perform(post("/sample/ftp/sync/upload-directory")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(uploadReq)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data.allSuccess").value(true))
                .andExpect(jsonPath("$.data.successCount").value(2))
                .andExpect(jsonPath("$.data.failureCount").value(0));

        // 2. 批量镜像下载目录到本地新工作区
        Path localDownloadTarget = tempDir.resolve("settlement_download");
        Files.createDirectories(localDownloadTarget);

        DirectorySyncRequest downloadReq = DirectorySyncRequest.builder()
                .localDir(localDownloadTarget.toAbsolutePath().toString())
                .remoteDir("/finance/settlement")
                .filterExtension(".csv")
                .failFast(false)
                .build();

        mockMvc.perform(post("/sample/ftp/sync/download-directory")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(downloadReq)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data.allSuccess").value(true))
                .andExpect(jsonPath("$.data.successCount").value(2));

        assertThat(Files.exists(localDownloadTarget.resolve("summary.csv"))).isTrue();
        assertThat(Files.exists(localDownloadTarget.resolve("batch_20261110/detail_01.csv"))).isTrue();
    }

    @Test
    @DisplayName("03. FtpTransferController: 大文件吞吐、流式进度追踪与 50MB 内存防爆")
    void testTransferController() throws Exception {
        // 1. 大文件上传测试（64KB，挂载防抖节流进度监听）
        LargeFileTransferRequest uploadReq = LargeFileTransferRequest.builder()
                .remotePath("/transfer/test-large.bin")
                .sizeKb(64)
                .build();

        mockMvc.perform(post("/sample/ftp/transfer/upload-large")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(uploadReq)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data.completed").value(true))
                .andExpect(jsonPath("$.data.percentage").value(100.0))
                .andExpect(jsonPath("$.data.bytesTransferred").value(64 * 1024));

        // 2. 大文件流式下载测试
        LargeFileTransferRequest downloadReq = LargeFileTransferRequest.builder()
                .remotePath("/transfer/test-large.bin")
                .build();

        mockMvc.perform(post("/sample/ftp/transfer/download-large")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(downloadReq)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data.completed").value(true))
                .andExpect(jsonPath("$.data.percentage").value(100.0));

        // 3. 安全内存下载端点测试（防 OOM 阈值守护）
        mockMvc.perform(get("/sample/ftp/transfer/download-safe")
                        .param("remotePath", "/transfer/test-large.bin"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data").value(64 * 1024));
    }

    @Test
    @DisplayName("04. FtpGovernanceController: 物理探针、动态切源与连接池状态")
    void testGovernanceController() throws Exception {
        // 1. 默认主节点连通性探针 (NOOP)
        mockMvc.perform(get("/sample/ftp/governance/probe-health"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data").value(true));

        // 2. 备用具名节点连通性探针 (backup)
        mockMvc.perform(get("/sample/ftp/governance/probe-health")
                        .param("serverName", "backup"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data").value(true));

        // 3. 强校验健康断言
        mockMvc.perform(post("/sample/ftp/governance/assert-healthy")
                        .param("serverName", "backup"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200));

        // 4. 动态切源写入内容至 backup 节点 (FtpContextHolder 栈式安全隔离)
        DynamicRoutingRequest routingReq = DynamicRoutingRequest.builder()
                .serverName("backup")
                .remotePath("/routing/backup-data.txt")
                .content("Secured Backup Datacenter Payload")
                .build();

        mockMvc.perform(post("/sample/ftp/governance/dynamic-routing-write")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(routingReq)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data").value(true));

        // 5. 动态切源读取 backup 节点内容
        mockMvc.perform(get("/sample/ftp/governance/dynamic-routing-read")
                        .param("serverName", "backup")
                        .param("remotePath", "/routing/backup-data.txt"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data").value("Secured Backup Datacenter Payload"));

        // 6. 查看连接池指标
        mockMvc.perform(get("/sample/ftp/governance/pool-stats")
                        .param("serverName", "default"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data.serverName").value("default"))
                .andExpect(jsonPath("$.data.idleConnections", notNullValue()))
                .andExpect(jsonPath("$.data.maxConnections").value(10));
    }

    @Test
    @DisplayName("05. 参数校验防御: 拦截空白路径请求")
    void testValidationFailure() throws Exception {
        WriteTextRequest invalidReq = WriteTextRequest.builder()
                .remotePath("") // 空白路径违背 @NotBlank
                .content("Some content")
                .build();

        mockMvc.perform(post("/sample/ftp/document/write-text")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(invalidReq)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(400));
    }
}
