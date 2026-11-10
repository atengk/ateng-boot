package io.github.atengk.boot.sample.ftp.controller;

import io.github.atengk.boot.core.model.Result;
import io.github.atengk.boot.ftp.model.FtpFileInfo;
import io.github.atengk.boot.sample.ftp.dto.AppendTextRequest;
import io.github.atengk.boot.sample.ftp.dto.CopyMoveRequest;
import io.github.atengk.boot.sample.ftp.dto.DeleteDirectoryRequest;
import io.github.atengk.boot.sample.ftp.dto.PathRequest;
import io.github.atengk.boot.sample.ftp.dto.RenameRequest;
import io.github.atengk.boot.sample.ftp.dto.UploadFileRequest;
import io.github.atengk.boot.sample.ftp.dto.WriteTextRequest;
import io.github.atengk.boot.sample.ftp.service.DocumentSyncScenarioService;
import io.github.atengk.boot.sample.ftp.vo.FtpFileInfoVO;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.util.StringUtils;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.List;

/**
 * 远程 FTP 日常文档生命周期管理控制器
 * <p>
 * 提供表单与磁盘文件上传、流式下载、纯文本与 JSON 读写、RFC 959 增量追加、
 * 跨路径原子复制移动、重命名、确定性删除与 RFC 3659 MLSD 机器解析等核心端点。
 * </p>
 *
 * @author Ateng
 * @since 2026-11-10
 */
@RestController
@RequestMapping("/sample/ftp/document")
@RequiredArgsConstructor
@Validated
@Slf4j
public class FtpDocumentController {

    private final DocumentSyncScenarioService documentSyncService;

    /**
     * 表单文件原子上传
     *
     * @param file       待上传的 MultipartFile 表单文件
     * @param remotePath 目标远程路径（可选，未指定时默认上传到 /upload/文件名）
     * @return 上传成功后的远程文件元数据
     */
    @PostMapping(value = "/upload", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public Result<FtpFileInfoVO> uploadMultipartFile(
            @RequestParam("file") MultipartFile file,
            @RequestParam(value = "remotePath", required = false) String remotePath) throws IOException {

        String originalFilename = file.getOriginalFilename();
        String targetPath = StringUtils.hasText(remotePath)
                ? remotePath
                : "/upload/" + (StringUtils.hasText(originalFilename) ? originalFilename : "unnamed.bin");

        try (InputStream in = file.getInputStream()) {
            documentSyncService.uploadStream(targetPath, in);
        }

        FtpFileInfo info = documentSyncService.getDocumentInfo(targetPath);
        return Result.success(toVO(info));
    }

    /**
     * 上传本地磁盘指定路径文件
     *
     * @param request 本地文件上传请求
     * @return 上传结果
     */
    @PostMapping("/upload-file")
    public Result<Boolean> uploadDiskFile(@Valid @RequestBody UploadFileRequest request) {
        File localFile = new File(request.getLocalPath());
        boolean success = documentSyncService.uploadFile(request.getRemotePath(), localFile);
        return Result.success(success);
    }

    /**
     * 快捷写入纯文本内容至远程文件
     *
     * @param request 文本写入请求
     * @return 写入结果
     */
    @PostMapping("/write-text")
    public Result<Boolean> writeText(@Valid @RequestBody WriteTextRequest request) {
        boolean success = documentSyncService.writeText(request.getRemotePath(), request.getContent());
        return Result.success(success);
    }

    /**
     * 快捷读取远程文件文本内容
     *
     * @param remotePath 远程文件路径
     * @return 文本内容
     */
    @GetMapping("/read-text")
    public Result<String> readText(@RequestParam("remotePath") @NotBlank(message = "远程文件路径不能为空") String remotePath) {
        String content = documentSyncService.readText(remotePath);
        return Result.success(content);
    }

    /**
     * 增量追加写入文本内容至远程已有文件末尾 (RFC 959 APPE)
     *
     * @param request 增量追加请求
     * @return 追加结果
     */
    @PostMapping("/append-text")
    public Result<Boolean> appendText(@Valid @RequestBody AppendTextRequest request) {
        boolean success = documentSyncService.appendText(request.getRemotePath(), request.getContent());
        return Result.success(success);
    }

    /**
     * 复制远程 FTP 文件到新路径（受管管道流原子对拷）
     *
     * @param request 复制请求
     * @return 复制结果
     */
    @PostMapping("/copy")
    public Result<Boolean> copyDocument(@Valid @RequestBody CopyMoveRequest request) {
        boolean success = documentSyncService.copyDocument(request.getSourcePath(), request.getTargetPath());
        return Result.success(success);
    }

    /**
     * 移动文件到目标目录下
     *
     * @param request 移动请求
     * @return 移动结果
     */
    @PostMapping("/move")
    public Result<Boolean> moveDocument(@Valid @RequestBody CopyMoveRequest request) {
        boolean success = documentSyncService.moveDocument(request.getSourcePath(), request.getTargetPath());
        return Result.success(success);
    }

    /**
     * 安全重命名远程文件或目录
     *
     * @param request 重命名请求
     * @return 重命名结果
     */
    @PostMapping("/rename")
    public Result<Boolean> renameDocument(@Valid @RequestBody RenameRequest request) {
        boolean success = documentSyncService.renameDocument(request.getSourcePath(), request.getTargetPath());
        return Result.success(success);
    }

    /**
     * 删除远程单个文件
     *
     * @param request 路径请求
     * @return 删除结果
     */
    @PostMapping("/delete-file")
    public Result<Boolean> deleteFile(@Valid @RequestBody PathRequest request) {
        boolean success = documentSyncService.deleteDocument(request.getPath());
        return Result.success(success);
    }

    /**
     * 递归删除远程目录及其所有子内容
     *
     * @param request 目录删除请求
     * @return 删除结果
     */
    @PostMapping("/delete-directory")
    public Result<Boolean> deleteDirectory(@Valid @RequestBody DeleteDirectoryRequest request) {
        boolean success = documentSyncService.deleteDirectory(request.getPath());
        return Result.success(success);
    }

    /**
     * 获取指定目录下的文件及子目录列表 (RFC 3659 MLSD)
     *
     * @param remoteDir 远程目录路径
     * @return 文件元数据列表
     */
    @GetMapping("/list-files")
    public Result<List<FtpFileInfoVO>> listFiles(@RequestParam("remoteDir") @NotBlank(message = "远程目录路径不能为空") String remoteDir) {
        List<FtpFileInfo> list = documentSyncService.listDocuments(remoteDir);
        if (list == null || list.isEmpty()) {
            return Result.success(Collections.emptyList());
        }
        return Result.success(list.stream().map(this::toVO).toList());
    }

    /**
     * 获取指定远程文件详细元数据
     *
     * @param remotePath 远程文件路径
     * @return 远程文件元数据
     */
    @GetMapping("/info")
    public Result<FtpFileInfoVO> getInfo(@RequestParam("remotePath") @NotBlank(message = "远程文件路径不能为空") String remotePath) {
        FtpFileInfo info = documentSyncService.getDocumentInfo(remotePath);
        return Result.success(toVO(info));
    }

    /**
     * 流式下载远程文件
     *
     * @param remotePath 远程文件路径
     * @param response   HTTP 响应对象
     */
    @GetMapping("/download")
    public void downloadFile(@RequestParam("remotePath") @NotBlank(message = "远程文件路径不能为空") String remotePath, HttpServletResponse response) throws IOException {
        String filename = remotePath.contains("/") ? remotePath.substring(remotePath.lastIndexOf('/') + 1) : remotePath;
        String encodedFilename = URLEncoder.encode(filename, StandardCharsets.UTF_8).replace("+", "%20");

        response.setContentType(MediaType.APPLICATION_OCTET_STREAM_VALUE);
        response.setHeader(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"" + encodedFilename + "\"; filename*=UTF-8''" + encodedFilename);

        documentSyncService.downloadToStream(remotePath, response.getOutputStream());
        response.flushBuffer();
    }

    private FtpFileInfoVO toVO(FtpFileInfo info) {
        if (info == null) {
            return null;
        }
        return FtpFileInfoVO.builder()
                .name(info.getName())
                .path(info.getPath())
                .size(info.getSize())
                .lastModified(info.getLastModified())
                .directory(info.isDirectory())
                .file(info.isFile())
                .build();
    }
}
