package io.github.atengk.boot.sample.ftp.controller;

import io.github.atengk.boot.core.model.Result;
import io.github.atengk.boot.ftp.context.FtpContextHolder;
import io.github.atengk.boot.ftp.exception.FtpException;
import io.github.atengk.boot.ftp.exception.FtpResultCode;
import io.github.atengk.boot.ftp.model.FtpFileInfo;
import io.github.atengk.boot.ftp.template.FtpTemplate;
import io.github.atengk.boot.sample.ftp.dto.FtpRenameRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.util.StreamUtils;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.io.InputStream;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.List;

/**
 * FTP 样例服务 Web 控制器
 * <p>
 * 演示基于 {@link FtpTemplate} 的流式上传下载、动态切源路由、文件系统管理与原生逃逸舱全景功能。
 * </p>
 *
 * @author Ateng
 * @since 2026-10-22
 */
@Slf4j
@Validated
@RestController
@RequestMapping("/api/ftp")
@RequiredArgsConstructor
public class FtpSampleController {

    private final FtpTemplate ftpTemplate;

    /**
     * 表单上传文件（支持原子上传与动态切源）
     *
     * @param file       待上传的表单文件
     * @param remotePath 目标远程路径（若未提供则使用文件名）
     * @param server     目标节点名称（可选，若提供则在指定节点执行）
     * @return 上传结果
     */
    @PostMapping("/upload")
    public Result<Boolean> upload(
            @RequestParam("file") MultipartFile file,
            @RequestParam(value = "remotePath", required = false) String remotePath,
            @RequestParam(value = "server", required = false) String server) {
        String targetPath = remotePath != null && !remotePath.isBlank()
                ? remotePath
                : "/" + file.getOriginalFilename();

        java.util.function.Supplier<Result<Boolean>> uploadAction = () -> {
            try (InputStream in = file.getInputStream()) {
                boolean success = ftpTemplate.upload(targetPath, in);
                return Result.success(success);
            } catch (IOException e) {
                throw new FtpException(FtpResultCode.UPLOAD_FAILED, "读取待上传文件流失败: " + e.getMessage(), e);
            }
        };

        if (server != null && !server.isBlank()) {
            return FtpContextHolder.runWith(server, uploadAction);
        }
        return uploadAction.get();
    }

    /**
     * 流式下载文件
     *
     * @param remotePath 远程文件绝对路径
     * @param response   HTTP 响应对象
     */
    @GetMapping("/download")
    public void download(
            @RequestParam("remotePath") @NotBlank(message = "远程路径不能为空") String remotePath,
            HttpServletResponse response) {
        String fileName = remotePath.substring(remotePath.lastIndexOf('/') + 1);
        String encodedFileName = URLEncoder.encode(fileName, StandardCharsets.UTF_8).replace("+", "%20");

        response.setContentType(MediaType.APPLICATION_OCTET_STREAM_VALUE);
        response.setHeader(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"" + encodedFileName + "\"");

        try {
            ftpTemplate.download(remotePath, response.getOutputStream());
            response.flushBuffer();
        } catch (IOException e) {
            throw new RuntimeException("写出下载响应流异常", e);
        }
    }

    /**
     * 便捷写入纯文本内容
     *
     * @param remotePath 远程文件路径
     * @param content    文本内容
     * @return 写入结果
     */
    @PostMapping("/write-text")
    public Result<Boolean> writeText(
            @RequestParam("remotePath") @NotBlank(message = "远程路径不能为空") String remotePath,
            @RequestParam("content") String content) {
        boolean success = ftpTemplate.writeString(remotePath, content);
        return Result.success(success);
    }

    /**
     * 便捷读取纯文本内容
     *
     * @param remotePath 远程文件路径
     * @return 文本内容
     */
    @GetMapping("/read-text")
    public Result<String> readText(
            @RequestParam("remotePath") @NotBlank(message = "远程路径不能为空") String remotePath) {
        String content = ftpTemplate.readString(remotePath);
        return Result.success(content);
    }

    /**
     * 获取文件详细元数据
     *
     * @param remotePath 远程文件路径
     * @return 文件元数据对象
     */
    @GetMapping("/info")
    public Result<FtpFileInfo> getFileInfo(
            @RequestParam("remotePath") @NotBlank(message = "远程路径不能为空") String remotePath) {
        FtpFileInfo info = ftpTemplate.getFileInfo(remotePath);
        return Result.success(info);
    }

    /**
     * 检查远程路径是否存在
     *
     * @param remotePath 远程路径
     * @return 是否存在
     */
    @GetMapping("/exists")
    public Result<Boolean> exists(
            @RequestParam("remotePath") @NotBlank(message = "远程路径不能为空") String remotePath) {
        boolean exists = ftpTemplate.exists(remotePath);
        return Result.success(exists);
    }

    /**
     * 列举指定目录下的文件与子目录列表
     *
     * @param remoteDir 远程目录绝对路径
     * @return 文件信息列表
     */
    @GetMapping("/list")
    public Result<List<FtpFileInfo>> listFiles(
            @RequestParam(value = "remoteDir", defaultValue = "/") String remoteDir) {
        List<FtpFileInfo> list = ftpTemplate.listFiles(remoteDir);
        return Result.success(list);
    }

    /**
     * 递归创建多级目录
     *
     * @param remoteDir 待创建的目录路径
     * @return 创建结果
     */
    @PostMapping("/mkdir")
    public Result<Boolean> makeDirectories(
            @RequestParam("remoteDir") @NotBlank(message = "目录路径不能为空") String remoteDir) {
        boolean success = ftpTemplate.makeDirectories(remoteDir);
        return Result.success(success);
    }

    /**
     * 重命名文件或目录
     *
     * @param request 重命名请求体
     * @return 操作结果
     */
    @PostMapping("/rename")
    public Result<Boolean> rename(@RequestBody @Valid FtpRenameRequest request) {
        boolean success = ftpTemplate.rename(request.getFromPath(), request.getToPath());
        return Result.success(success);
    }

    /**
     * 移动文件至新目标路径
     *
     * @param request 移动请求体
     * @return 操作结果
     */
    @PostMapping("/move")
    public Result<Boolean> move(@RequestBody @Valid FtpRenameRequest request) {
        boolean success = ftpTemplate.move(request.getFromPath(), request.getToPath());
        return Result.success(success);
    }

    /**
     * 删除指定远程文件
     *
     * @param remotePath 待删除文件路径
     * @return 删除结果
     */
    @DeleteMapping("/delete-file")
    public Result<Boolean> deleteFile(
            @RequestParam("remotePath") @NotBlank(message = "文件路径不能为空") String remotePath) {
        boolean success = ftpTemplate.deleteFile(remotePath);
        return Result.success(success);
    }

    /**
     * 递归删除目录及其所有子文件
     *
     * @param remoteDir 待删除目录路径
     * @return 删除结果
     */
    @DeleteMapping("/delete-dir")
    public Result<Boolean> deleteDirectory(
            @RequestParam("remoteDir") @NotBlank(message = "目录路径不能为空") String remoteDir) {
        boolean success = ftpTemplate.deleteDirectory(remoteDir);
        return Result.success(success);
    }

    /**
     * 动态切源演示：在指定数据源节点下读取文件
     *
     * @param server     目标节点
     * @param remotePath 远程文件路径
     * @return 文件内容
     */
    @GetMapping("/routed-read")
    public Result<String> routedRead(
            @RequestParam("server") @NotBlank(message = "目标节点不能为空") String server,
            @RequestParam("remotePath") @NotBlank(message = "远程路径不能为空") String remotePath) {
        String content = FtpContextHolder.runWith(server, () -> ftpTemplate.readString(remotePath));
        return Result.success(content);
    }

    /**
     * 逃逸舱模式：执行原生 FTPClient 指令（例如获取工作目录）
     *
     * @return 当前原生会话工作目录
     */
    @GetMapping("/native-pwd")
    public Result<String> nativePwd() {
        String pwd = ftpTemplate.execute(client -> client.printWorkingDirectory());
        return Result.success(pwd);
    }
}
