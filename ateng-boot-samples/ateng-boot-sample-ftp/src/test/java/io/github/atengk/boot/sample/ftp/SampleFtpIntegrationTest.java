package io.github.atengk.boot.sample.ftp;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.atengk.boot.ftp.context.FtpContextHolder;
import io.github.atengk.boot.sample.ftp.dto.FtpRenameRequest;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
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

import java.nio.charset.StandardCharsets;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.is;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * FTP 样例服务端到端真实集成测试
 * <p>
 * 覆盖：
 * 1. MultipartFile 表单原子上传与流式下载（含 Content-Disposition 与 UTF-8 编码）
 * 2. 纯文本快捷读写 (readText / writeText)
 * 3. 详细元数据获取 (info) 与存在性探查 (exists)
 * 4. 目录递归创建 (mkdir) 与 MLSD 目录列举 (list)
 * 5. 文件安全重命名 (rename) 与跨目录移动 (move)
 * 6. 文件与目录的确定性删除 (delete-file / delete-dir)
 * 7. 动态切源路由 (routed-read / upload 到 backup 节点)
 * 8. 原生会话逃逸舱 (native-pwd)
 * 9. 路径穿越与高危字符安全防御 (Path Traversal Guard)
 * 10. JSR-303 参数格式校验 Fail-Fast 防御
 * </p>
 *
 * @author Ateng
 * @since 2026-10-22
 */
@SpringBootTest
@AutoConfigureMockMvc
public class SampleFtpIntegrationTest {

    private static FakeFtpServer defaultServer;
    private static FakeFtpServer backupServer;

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @BeforeAll
    static void startServers() {
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

    @DynamicPropertySource
    static void configureFtpProperties(DynamicPropertyRegistry registry) {
        registry.add("ateng.boot.ftp.port", () -> defaultServer.getServerControlPort());
        registry.add("ateng.boot.ftp.servers.backup.port", () -> backupServer.getServerControlPort());
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
    @DisplayName("01. 表单文件原子上传与 HTTP 流式下载自验")
    void testUploadAndDownload() throws Exception {
        byte[] fileBytes = "Hello ateng-boot FTP Integration Test!".getBytes(StandardCharsets.UTF_8);
        MockMultipartFile file = new MockMultipartFile("file", "sample-doc.txt", "text/plain", fileBytes);

        // 1. 上传文件
        mockMvc.perform(multipart("/api/ftp/upload")
                        .file(file)
                        .param("remotePath", "/data/sample-doc.txt"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code", is(200)))
                .andExpect(jsonPath("$.data", is(true)));

        // 2. 流式下载文件
        mockMvc.perform(get("/api/ftp/download")
                        .param("remotePath", "/data/sample-doc.txt"))
                .andExpect(status().isOk())
                .andExpect(header().string(HttpHeaders.CONTENT_DISPOSITION, containsString("sample-doc.txt")))
                .andExpect(content().bytes(fileBytes));
    }

    @Test
    @DisplayName("02. 纯文本快捷写入与读取自验")
    void testWriteAndReadText() throws Exception {
        String testContent = "Quick Text Content for FTP Test";

        // 1. 写入文本
        mockMvc.perform(post("/api/ftp/write-text")
                        .param("remotePath", "/text/config.json")
                        .param("content", testContent))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code", is(200)))
                .andExpect(jsonPath("$.data", is(true)));

        // 2. 读取文本
        mockMvc.perform(get("/api/ftp/read-text")
                        .param("remotePath", "/text/config.json"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code", is(200)))
                .andExpect(jsonPath("$.data", is(testContent)));
    }

    @Test
    @DisplayName("03. 文件元数据获取与存在性探测自验")
    void testFileInfoAndExists() throws Exception {
        // 先确保测试文件已存在
        mockMvc.perform(post("/api/ftp/write-text")
                        .param("remotePath", "/text/probe.json")
                        .param("content", "{\"probe\":true}"))
                .andExpect(status().isOk());

        // 1. 探测已存在的文件
        mockMvc.perform(get("/api/ftp/exists")
                        .param("remotePath", "/text/probe.json"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data", is(true)));

        // 2. 获取文件元数据
        mockMvc.perform(get("/api/ftp/info")
                        .param("remotePath", "/text/probe.json"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.name", is("probe.json")))
                .andExpect(jsonPath("$.data.directory", is(false)))
                .andExpect(jsonPath("$.data.size", is("14")));

        // 3. 探测不存在的文件
        mockMvc.perform(get("/api/ftp/exists")
                        .param("remotePath", "/text/not-found-anywhere.json"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data", is(false)));
    }

    @Test
    @DisplayName("04. 递归创建多级目录与目录列表检索自验")
    void testListFilesAndMakeDirectories() throws Exception {
        // 1. 递归创建多级目录
        mockMvc.perform(post("/api/ftp/mkdir")
                        .param("remoteDir", "/tree/level1/level2"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data", is(true)));

        // 2. 在多级目录下写入文件
        mockMvc.perform(post("/api/ftp/write-text")
                        .param("remotePath", "/tree/level1/level2/nested.txt")
                        .param("content", "Nested file content"))
                .andExpect(status().isOk());

        // 3. 列出目录内容
        mockMvc.perform(get("/api/ftp/list")
                        .param("remoteDir", "/tree/level1/level2"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data", hasSize(1)))
                .andExpect(jsonPath("$.data[0].name", is("nested.txt")));
    }

    @Test
    @DisplayName("05. 文件重命名与跨目录移动自验")
    void testRenameAndMove() throws Exception {
        // 1. 写入初始文件
        mockMvc.perform(post("/api/ftp/write-text")
                        .param("remotePath", "/ops/original.txt")
                        .param("content", "Move & Rename Test"))
                .andExpect(status().isOk());

        // 2. 重命名同目录
        FtpRenameRequest renameReq = new FtpRenameRequest();
        renameReq.setFromPath("/ops/original.txt");
        renameReq.setToPath("/ops/renamed.txt");

        mockMvc.perform(post("/api/ftp/rename")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(renameReq)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data", is(true)));

        // 3. 跨目录移动（移动到目标父目录 /ops/target_dir）
        FtpRenameRequest moveReq = new FtpRenameRequest();
        moveReq.setFromPath("/ops/renamed.txt");
        moveReq.setToPath("/ops/target_dir");

        mockMvc.perform(post("/api/ftp/move")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(moveReq)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data", is(true)));

        // 4. 验证移动后内容可在目标目录下读取
        mockMvc.perform(get("/api/ftp/read-text")
                        .param("remotePath", "/ops/target_dir/renamed.txt"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data", is("Move & Rename Test")));
    }

    @Test
    @DisplayName("06. 文件删除与目录级联删除自验")
    void testDeleteFileAndDirectory() throws Exception {
        // 1. 准备待删文件
        mockMvc.perform(post("/api/ftp/write-text")
                        .param("remotePath", "/cleanup/subdir/temp.txt")
                        .param("content", "To be deleted"))
                .andExpect(status().isOk());

        // 2. 删除文件
        mockMvc.perform(delete("/api/ftp/delete-file")
                        .param("remotePath", "/cleanup/subdir/temp.txt"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data", is(true)));

        // 3. 删除目录
        mockMvc.perform(delete("/api/ftp/delete-dir")
                        .param("remoteDir", "/cleanup"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data", is(true)));
    }

    @Test
    @DisplayName("07. 多节点动态切源路由隔离自验")
    void testDynamicRoutingBetweenNodes() throws Exception {
        // 1. 在 backup 节点上传文件
        byte[] backupBytes = "Isolated content in backup server".getBytes(StandardCharsets.UTF_8);
        MockMultipartFile file = new MockMultipartFile("file", "backup.log", "text/plain", backupBytes);

        mockMvc.perform(multipart("/api/ftp/upload")
                        .file(file)
                        .param("remotePath", "/cluster/backup.log")
                        .param("server", "backup"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data", is(true)));

        // 2. 切源读取 backup 节点数据成功
        mockMvc.perform(get("/api/ftp/routed-read")
                        .param("server", "backup")
                        .param("remotePath", "/cluster/backup.log"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data", is("Isolated content in backup server")));

        // 3. 在默认主节点读取该路径应不存在（隔离性保障）
        mockMvc.perform(get("/api/ftp/exists")
                        .param("remotePath", "/cluster/backup.log"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data", is(false)));
    }

    @Test
    @DisplayName("08. 逃逸舱模式执行原生会话指令自验")
    void testNativeEscapeHatch() throws Exception {
        mockMvc.perform(get("/api/ftp/native-pwd"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code", is(200)))
                .andExpect(jsonPath("$.data", is("/")));
    }

    @Test
    @DisplayName("09. 路径穿越与高危字符安全防御自验")
    void testPathTraversalDefense() throws Exception {
        // 试图访问父级路径 /../../etc/passwd，触发 FtpException(INVALID_PATH) 阻断并由统一异常拦截器转译为业务错误契约
        mockMvc.perform(get("/api/ftp/exists")
                        .param("remotePath", "/../../etc/passwd"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code", is(51007)));
    }

    @Test
    @DisplayName("10. JSR-303 参数格式校验 Fail-Fast 自验")
    void testValidationFailFast() throws Exception {
        // 请求参数为空白字符串触发 @NotBlank 拦截
        mockMvc.perform(get("/api/ftp/read-text")
                        .param("remotePath", "   "))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code", is(400)))
                .andExpect(jsonPath("$.msg", containsString("远程路径不能为空")));
    }
}
