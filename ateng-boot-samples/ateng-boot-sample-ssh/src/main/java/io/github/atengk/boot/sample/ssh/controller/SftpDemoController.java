package io.github.atengk.boot.sample.ssh.controller;

import io.github.atengk.boot.core.model.Result;
import io.github.atengk.boot.ssh.model.SftpFileInfo;
import io.github.atengk.boot.ssh.template.SftpTemplate;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

/**
 * SFTP 工业级文件传输样例控制器
 * <p>
 * 遵循显式强类型 {@link Result} 契约，演示 SFTP 原子上传带流式进度监听、流式文件下载、
 * 目录项列表检索、DFS 深度优先递归删除非空目录及目录树整包双向同步。
 * </p>
 *
 * @author Ateng
 * @since 2026-10-23
 */
@Slf4j
@Validated
@RestController
@RequestMapping("/api/sftp")
@RequiredArgsConstructor
public class SftpDemoController {

    private final SftpTemplate sftpTemplate;

    /**
     * 流式上传文件并挂载实时进度监听器 (原子写入 .uploading.tmp 后 rename)
     *
     * @param file       上传的多部件文件
     * @param remotePath 目标远程绝对路径
     * @return 上传结果元数据
     */
    @PostMapping(value = "/upload", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public Result<Map<String, Object>> uploadFile(@RequestParam("file") @NotNull(message = "上传文件不能为空") MultipartFile file,
                                                  @RequestParam("remotePath") @NotBlank(message = "远程路径不能为空") String remotePath) throws IOException {
        AtomicLong lastReportedBytes = new AtomicLong(0);
        long fileSize = file.getSize();

        try (InputStream in = file.getInputStream()) {
            sftpTemplate.upload(remotePath, in, (transferred, total) -> {
                lastReportedBytes.set(transferred);
                log.debug("SFTP 实时上传进度: transferred={}/{} bytes", transferred, total);
            });
        }

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("remotePath", remotePath);
        result.put("fileName", file.getOriginalFilename());
        result.put("transferredBytes", lastReportedBytes.get());
        result.put("fileSize", fileSize);
        result.put("exists", sftpTemplate.exists(remotePath));
        return Result.success(result);
    }

    /**
     * 流式下载远程文件并直传 HTTP 响应流
     *
     * @param remotePath 远程文件路径
     * @param response   HTTP 响应载体
     */
    @GetMapping("/download")
    public void downloadFile(@RequestParam("remotePath") @NotBlank(message = "远程路径不能为空") String remotePath,
                             HttpServletResponse response) throws IOException {
        String fileName = new File(remotePath).getName();
        response.setContentType(MediaType.APPLICATION_OCTET_STREAM_VALUE);
        response.setHeader(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"" + fileName + "\"");

        sftpTemplate.download(remotePath, response.getOutputStream(), (transferred, total) ->
                log.debug("SFTP 实时下载进度: transferred={}/{} bytes", transferred, total));
        response.flushBuffer();
    }

    /**
     * 检索远程目录项文件元数据列表
     *
     * @param remotePath 远程目录路径
     * @return 强类型文件元数据列表
     */
    @GetMapping("/list")
    public Result<List<SftpFileInfo>> listDirectory(@RequestParam("remotePath") @NotBlank(message = "远程路径不能为空") String remotePath) {
        List<SftpFileInfo> entries = sftpTemplate.list(remotePath);
        return Result.success(entries);
    }

    /**
     * DFS 深度优先递归删除远程非空目录树
     *
     * @param remotePath 待删除的远程目录路径
     * @return 递归删除是否成功
     */
    @PostMapping("/delete-directory")
    public Result<Boolean> deleteDirectoryRecursively(@RequestParam("remotePath") @NotBlank(message = "远程路径不能为空") String remotePath) {
        boolean deleted = sftpTemplate.deleteRecursively(remotePath);
        return Result.success(deleted);
    }

    /**
     * 演示目录树整包双向同步（先将临时本地目录整包同步至远程，再将远程整包下载回另一本地目录）
     *
     * @param remoteDir 远程同步目标目录
     * @return 双向同步验证结果
     */
    @PostMapping("/sync-directory")
    public Result<Map<String, Object>> syncDirectoryTree(@RequestParam("remoteDir") @NotBlank(message = "远程目录不能为空") String remoteDir) throws IOException {
        Path tempLocalUpload = Files.createTempDirectory("sftp-sync-upload-");
        Path tempLocalDownload = Files.createTempDirectory("sftp-sync-download-");

        try {
            // 1. 本地构造包含多级子目录与空目录的测试目录树
            Files.createDirectories(tempLocalUpload.resolve("sub/deep/empty-dir"));
            Files.writeString(tempLocalUpload.resolve("root.txt"), "hello root");
            Files.writeString(tempLocalUpload.resolve("sub/file.txt"), "hello sub");

            // 2. 整包递归上传目录树至远程
            sftpTemplate.uploadDirectory(tempLocalUpload.toFile(), remoteDir);

            // 3. 将远程整包目录树完整镜像下载回本地
            sftpTemplate.downloadDirectory(remoteDir, tempLocalDownload.toFile());

            boolean hasRoot = Files.exists(tempLocalDownload.resolve("root.txt"));
            boolean hasSub = Files.exists(tempLocalDownload.resolve("sub/file.txt"));
            boolean hasEmpty = Files.isDirectory(tempLocalDownload.resolve("sub/deep/empty-dir"));

            Map<String, Object> syncResult = new LinkedHashMap<>();
            syncResult.put("remoteDir", remoteDir);
            syncResult.put("hasRoot", hasRoot);
            syncResult.put("hasSub", hasSub);
            syncResult.put("hasEmpty", hasEmpty);
            syncResult.put("success", hasRoot && hasSub && hasEmpty);
            return Result.success(syncResult);
        } finally {
            // 清理本地临时测试目录
            deleteLocalDirectoryQuietly(tempLocalUpload);
            deleteLocalDirectoryQuietly(tempLocalDownload);
        }
    }

    private void deleteLocalDirectoryQuietly(Path path) {
        try {
            if (Files.exists(path)) {
                try (var stream = Files.walk(path)) {
                    stream.sorted((a, b) -> b.compareTo(a))
                            .forEach(p -> {
                                try {
                                    Files.deleteIfExists(p);
                                } catch (IOException ignored) {
                                }
                            });
                }
            }
        } catch (Exception ignored) {
        }
    }
}
