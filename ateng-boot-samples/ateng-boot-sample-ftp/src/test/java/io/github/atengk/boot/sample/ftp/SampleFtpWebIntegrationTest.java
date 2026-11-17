package io.github.atengk.boot.sample.ftp;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.atengk.boot.ftp.context.FtpContextHolder;
import io.github.atengk.boot.ftp.template.FtpTemplate;
import io.github.atengk.boot.sample.ftp.dto.AppendTextRequest;
import io.github.atengk.boot.sample.ftp.dto.CopyMoveRequest;
import io.github.atengk.boot.sample.ftp.dto.DeleteDirectoryRequest;
import io.github.atengk.boot.sample.ftp.dto.DirectorySyncRequest;
import io.github.atengk.boot.sample.ftp.dto.DynamicRoutingRequest;
import io.github.atengk.boot.sample.ftp.dto.LargeFileTransferRequest;
import io.github.atengk.boot.sample.ftp.dto.PathRequest;
import io.github.atengk.boot.sample.ftp.dto.RegisterNodeRequest;
import io.github.atengk.boot.sample.ftp.dto.RenameRequest;
import io.github.atengk.boot.sample.ftp.dto.ResumeTransferRequest;
import io.github.atengk.boot.sample.ftp.dto.SentinelUploadRequest;
import io.github.atengk.boot.sample.ftp.dto.UnregisterNodeRequest;
import io.github.atengk.boot.sample.ftp.dto.UploadFileRequest;
import io.github.atengk.boot.sample.ftp.dto.VerifyHashRequest;
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
import java.security.MessageDigest;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.Locale;

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

    @Autowired
    private FtpTemplate ftpTemplate;

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

        // 6. 查看连接池指标（原生 FtpPoolStats 门面映射）
        mockMvc.perform(get("/sample/ftp/governance/pool-stats")
                        .param("serverName", "default"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data.serverName").value("default"))
                .andExpect(jsonPath("$.data.idleConnections", notNullValue()))
                .andExpect(jsonPath("$.data.waitingBorrowers").value(0))
                .andExpect(jsonPath("$.data.maxIdle").value(8))
                .andExpect(jsonPath("$.data.minIdle").value(2))
                .andExpect(jsonPath("$.data.maxConnections").value(10));
    }

    @Test
    @DisplayName("04.1 FtpGovernanceController: 动态节点热注册、切源读写、安全注销与 default 防护")
    void testDynamicNodeRegisterAndUnregisterLifecycle() throws Exception {
        FakeFtpServer dynamicServer = new FakeFtpServer();
        dynamicServer.setServerControlPort(0);
        FileSystem dynFs = new UnixFakeFileSystem();
        dynFs.add(new DirectoryEntry("/"));
        dynamicServer.setFileSystem(dynFs);
        dynamicServer.addUserAccount(new UserAccount("dyn_user", "dyn_pass", "/"));
        dynamicServer.start();

        try {
            // 1. 动态热注册 tenant-dynamic 节点
            RegisterNodeRequest registerReq = RegisterNodeRequest.builder()
                    .serverName("tenant-dynamic")
                    .host("127.0.0.1")
                    .port(dynamicServer.getServerControlPort())
                    .username("dyn_user")
                    .password("dyn_pass")
                    .build();

            mockMvc.perform(post("/sample/ftp/governance/register-node")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(registerReq)))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.code").value(200))
                    .andExpect(jsonPath("$.data.serverName").value("tenant-dynamic"))
                    .andExpect(jsonPath("$.data.maxConnections").value(10));

            // 2. 使用 FtpContextHolder 动态切源至新注册节点写入与读取
            DynamicRoutingRequest writeReq = DynamicRoutingRequest.builder()
                    .serverName("tenant-dynamic")
                    .remotePath("/tenant/hello.txt")
                    .content("Dynamic Node Content 2026")
                    .build();

            mockMvc.perform(post("/sample/ftp/governance/dynamic-routing-write")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(writeReq)))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.code").value(200))
                    .andExpect(jsonPath("$.data").value(true));

            mockMvc.perform(get("/sample/ftp/governance/dynamic-routing-read")
                            .param("serverName", "tenant-dynamic")
                            .param("remotePath", "/tenant/hello.txt"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.code").value(200))
                    .andExpect(jsonPath("$.data").value("Dynamic Node Content 2026"));

            // 3. 注销 tenant-dynamic 节点并确认再次查询返回 SERVER_NOT_FOUND
            UnregisterNodeRequest unregisterReq = UnregisterNodeRequest.builder()
                    .serverName("tenant-dynamic")
                    .build();

            mockMvc.perform(post("/sample/ftp/governance/unregister-node")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(unregisterReq)))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.code").value(200))
                    .andExpect(jsonPath("$.data").value(true));

            mockMvc.perform(get("/sample/ftp/governance/pool-stats")
                            .param("serverName", "tenant-dynamic"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.code").value(51010));

            // 4. 严禁注销 default 主节点
            UnregisterNodeRequest defaultUnregisterReq = UnregisterNodeRequest.builder()
                    .serverName("default")
                    .build();

            mockMvc.perform(post("/sample/ftp/governance/unregister-node")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(defaultUnregisterReq)))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.code").value(51009));
        } finally {
            dynamicServer.stop();
        }
    }

    @Test
    @DisplayName("05. 参数校验防御: 拦截空白路径与非法数值请求")
    void testValidationFailure() throws Exception {
        // 1. POST 请求体 DTO @NotBlank 校验防御
        WriteTextRequest invalidReq = WriteTextRequest.builder()
                .remotePath("") // 空白路径违背 @NotBlank
                .content("Some content")
                .build();

        mockMvc.perform(post("/sample/ftp/document/write-text")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(invalidReq)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(400));

        // 2. POST 请求体 DTO @Positive 数值校验防御
        LargeFileTransferRequest invalidSizeReq = LargeFileTransferRequest.builder()
                .remotePath("/transfer/test.bin")
                .sizeKb(-1) // 负数违背 @Positive
                .build();

        mockMvc.perform(post("/sample/ftp/transfer/upload-large")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(invalidSizeReq)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(400));

        // 3. GET 查询参数 @NotBlank 第一道防线 Fail-Fast 校验防御
        mockMvc.perform(get("/sample/ftp/document/read-text")
                        .param("remotePath", ""))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(400));

        mockMvc.perform(get("/sample/ftp/governance/dynamic-routing-read")
                        .param("serverName", "")
                        .param("remotePath", "/some/path.txt"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(400));

        mockMvc.perform(get("/sample/ftp/transfer/download-safe")
                        .param("remotePath", ""))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(400));

        // 4. 动态节点注册/注销 DTO JSR-303 校验防御
        RegisterNodeRequest invalidRegisterReq = RegisterNodeRequest.builder()
                .serverName("")
                .host("127.0.0.1")
                .port(0)
                .username("user")
                .build();
        mockMvc.perform(post("/sample/ftp/governance/register-node")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(invalidRegisterReq)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(400));

        UnregisterNodeRequest invalidUnregisterReq = UnregisterNodeRequest.builder()
                .serverName("")
                .build();
        mockMvc.perform(post("/sample/ftp/governance/unregister-node")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(invalidUnregisterReq)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(400));

        // 5. 断点续传、摘要校验与信标上传 JSR-303 校验防御
        ResumeTransferRequest invalidResumeReq = ResumeTransferRequest.builder()
                .remotePath("")
                .localPath("")
                .build();
        mockMvc.perform(post("/sample/ftp/transfer/upload-resume")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(invalidResumeReq)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(400));
        mockMvc.perform(post("/sample/ftp/transfer/download-resume")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(invalidResumeReq)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(400));

        mockMvc.perform(get("/sample/ftp/document/hash")
                        .param("remotePath", ""))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(400));

        VerifyHashRequest invalidVerifyReq = VerifyHashRequest.builder()
                .remotePath("/test.bin")
                .expectedHex("")
                .build();
        mockMvc.perform(post("/sample/ftp/document/verify-hash")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(invalidVerifyReq)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(400));

        SentinelUploadRequest invalidSentinelReq = SentinelUploadRequest.builder()
                .remotePath("")
                .localPath("")
                .build();
        mockMvc.perform(post("/sample/ftp/sync/upload-with-sentinel")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(invalidSentinelReq)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(400));
    }

    @Test
    @DisplayName("06. FtpTransfer/Document/Sync: 断点续传、零落盘摘要校验与信标伴随上传端到端联调")
    void testResumableChecksumAndSentinelWorkflows(@TempDir Path tempDir) throws Exception {
        byte[] payload = new byte[96 * 1024];
        for (int i = 0; i < payload.length; i++) {
            payload[i] = (byte) ((i * 29 + 11) & 0xFF);
        }
        String expectedSha256 = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(payload));
        String expectedMd5 = HexFormat.of().formatHex(MessageDigest.getInstance("MD5").digest(payload));

        // 1. 准备本地大文件与远端 .tmp 断点分片，测试断点上传
        Path sourceFile = tempDir.resolve("resume-source.bin");
        Files.write(sourceFile, payload);
        String remoteResumePath = "/transfer/resume-sample.bin";
        ftpTemplate.upload(remoteResumePath + ".tmp", Arrays.copyOf(payload, 32 * 1024));

        ResumeTransferRequest uploadResumeReq = ResumeTransferRequest.builder()
                .remotePath(remoteResumePath)
                .localPath(sourceFile.toAbsolutePath().toString())
                .build();
        mockMvc.perform(post("/sample/ftp/transfer/upload-resume")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(uploadResumeReq)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data.completed").value(true))
                .andExpect(jsonPath("$.data.percentage").value(100.0))
                .andExpect(jsonPath("$.data.bytesTransferred").value(payload.length));

        assertThat(ftpTemplate.downloadToBytes(remoteResumePath)).isEqualTo(payload);

        // 2. 准备本地不完整下载文件，测试断点下载
        Path downloadTarget = tempDir.resolve("resume-downloaded.bin");
        Files.write(downloadTarget, Arrays.copyOf(payload, 24 * 1024));
        ResumeTransferRequest downloadResumeReq = ResumeTransferRequest.builder()
                .remotePath(remoteResumePath)
                .localPath(downloadTarget.toAbsolutePath().toString())
                .build();
        mockMvc.perform(post("/sample/ftp/transfer/download-resume")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(downloadResumeReq)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data.completed").value(true))
                .andExpect(jsonPath("$.data.percentage").value(100.0))
                .andExpect(jsonPath("$.data.bytesTransferred").value(payload.length));

        assertThat(Files.readAllBytes(downloadTarget)).isEqualTo(payload);

        // 3. 零落盘流式摘要计算与常量时间比对
        mockMvc.perform(get("/sample/ftp/document/hash")
                        .param("remotePath", remoteResumePath)
                        .param("algorithm", "SHA-256"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data").value(expectedSha256));

        mockMvc.perform(get("/sample/ftp/document/hash")
                        .param("remotePath", remoteResumePath)
                        .param("algorithm", "MD5"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data").value(expectedMd5));

        VerifyHashRequest validVerifyReq = VerifyHashRequest.builder()
                .remotePath(remoteResumePath)
                .expectedHex(expectedSha256.toUpperCase(Locale.ROOT))
                .algorithm("SHA-256")
                .build();
        mockMvc.perform(post("/sample/ftp/document/verify-hash")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(validVerifyReq)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data").value(true));

        VerifyHashRequest tamperedVerifyReq = VerifyHashRequest.builder()
                .remotePath(remoteResumePath)
                .expectedHex("0".repeat(expectedSha256.length()))
                .algorithm("SHA-256")
                .build();
        mockMvc.perform(post("/sample/ftp/document/verify-hash")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(tamperedVerifyReq)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data").value(false));

        // 4. 对账就绪信标文件伴随上传 (.ok 与 .sha256)
        Path settlementFile = tempDir.resolve("settle-batch.csv");
        byte[] csvBytes = "order_no,amount\n2026111701,188.00\n".getBytes(StandardCharsets.UTF_8);
        Files.write(settlementFile, csvBytes);
        String csvSha256 = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(csvBytes));

        SentinelUploadRequest okSentinelReq = SentinelUploadRequest.builder()
                .remotePath("/finance/sentinel/settle-batch.csv")
                .localPath(settlementFile.toAbsolutePath().toString())
                .sentinelSuffix(".ok")
                .build();
        mockMvc.perform(post("/sample/ftp/sync/upload-with-sentinel")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(okSentinelReq)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data.remotePath").value("/finance/sentinel/settle-batch.csv"))
                .andExpect(jsonPath("$.data.sentinelPath").value("/finance/sentinel/settle-batch.csv.ok"))
                .andExpect(jsonPath("$.data.fileSize").value(csvBytes.length))
                .andExpect(jsonPath("$.data.sentinelContent")
                        .value(csvSha256 + "  settle-batch.csv  " + csvBytes.length + "\n"));

        SentinelUploadRequest shaSentinelReq = SentinelUploadRequest.builder()
                .remotePath("/finance/sentinel/settle-batch-digest.csv")
                .localPath(settlementFile.toAbsolutePath().toString())
                .sentinelSuffix(".sha256")
                .build();
        mockMvc.perform(post("/sample/ftp/sync/upload-with-sentinel")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(shaSentinelReq)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data.sentinelPath").value("/finance/sentinel/settle-batch-digest.csv.sha256"))
                .andExpect(jsonPath("$.data.sentinelContent").value(csvSha256 + "  settle-batch-digest.csv\n"));
    }
}
