package io.github.atengk.boot.sample.ssh.controller;

import io.github.atengk.boot.core.model.Result;
import io.github.atengk.boot.sample.ssh.dto.SftpAppendTextRequest;
import io.github.atengk.boot.sample.ssh.dto.SftpCopyMoveRequest;
import io.github.atengk.boot.sample.ssh.dto.SftpPathRequest;
import io.github.atengk.boot.sample.ssh.dto.SftpRenameRequest;
import io.github.atengk.boot.sample.ssh.dto.SftpWriteTextRequest;
import io.github.atengk.boot.sample.ssh.service.SftpScenarioService;
import io.github.atengk.boot.sample.ssh.vo.SftpFileInfoVO;
import io.github.atengk.boot.ssh.model.SftpFileInfo;
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

import java.io.IOException;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;

/**
 * SFTP 日常文件与文档生命周期管理控制器
 * <p>
 * 提供表单文件原子上传、流式下载、纯文本配置读写、文件增量追加写、
 * 跨目录移动、原子重命名、元数据查询、存在性判断与单文件删除等核心接口。
 * </p>
 *
 * @author Ateng
 * @since 2026-11-10
 */
@Slf4j
@Validated
@RestController
@RequestMapping("/sample/ssh/sftp-doc")
@RequiredArgsConstructor
public class SftpDocumentController {

    private final SftpScenarioService sftpScenarioService;

    /**
     * 表单文件原子上传（先写临时后缀文件后原子 rename，防脏读）
     *
     * @param file       待上传的表单文件
     * @param remotePath 远端目标路径
     * @return 成功提示
     * @throws IOException IO 异常
     */
    @PostMapping(value = "/upload", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public Result<String> upload(@RequestParam("file") MultipartFile file,
                                 @RequestParam("remotePath") @NotBlank(message = "远程路径不能为空") String remotePath) throws IOException {
        log.info("SftpDocumentController: 收到表单文件上传请求 [originalFilename={}, size={}, remotePath={}]",
                file.getOriginalFilename(), file.getSize(), remotePath);
        sftpScenarioService.upload(remotePath, file.getBytes());
        return Result.success("上传成功: " + remotePath);
    }

    /**
     * 流式下载远端文件（直接输出二进制附件流）
     *
     * @param remotePath 远端目标路径
     * @param response   HTTP 响应载体
     * @throws IOException IO 异常
     */
    @GetMapping("/download")
    public void download(@RequestParam("remotePath") @NotBlank(message = "远程路径不能为空") String remotePath,
                         HttpServletResponse response) throws IOException {
        log.info("SftpDocumentController: 收到流式文件下载请求 [remotePath={}]", remotePath);
        byte[] data = sftpScenarioService.download(remotePath);

        String filename = StringUtils.getFilename(remotePath);
        if (!StringUtils.hasText(filename)) {
            filename = "download.bin";
        }
        String encodedFilename = URLEncoder.encode(filename, StandardCharsets.UTF_8).replace("+", "%20");

        response.setContentType(MediaType.APPLICATION_OCTET_STREAM_VALUE);
        response.setHeader(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"" + encodedFilename + "\"; filename*=UTF-8''" + encodedFilename);
        response.setContentLength(data.length);
        response.getOutputStream().write(data);
        response.getOutputStream().flush();
    }

    /**
     * 快捷写入 UTF-8 纯文本配置文件
     *
     * @param request 写入请求载荷
     * @return 成功状态
     */
    @PostMapping("/write-text")
    public Result<Boolean> writeText(@Valid @RequestBody SftpWriteTextRequest request) {
        log.info("SftpDocumentController: 收到写入文本配置请求 [remotePath={}]", request.getRemotePath());
        sftpScenarioService.writeConfig(request.getRemotePath(), request.getContent());
        return Result.success(true);
    }

    /**
     * 快捷读取远端 UTF-8 纯文本配置文件
     *
     * @param remotePath 远端路径
     * @return 文本内容
     */
    @GetMapping("/read-text")
    public Result<String> readText(@RequestParam("remotePath") @NotBlank(message = "远程路径不能为空") String remotePath) {
        log.info("SftpDocumentController: 收到读取文本配置请求 [remotePath={}]", remotePath);
        String content = sftpScenarioService.readConfig(remotePath);
        return Result.success(content);
    }

    /**
     * 向远端既有文件尾部增量追加数据
     *
     * @param request 追加请求载荷
     * @return 成功状态
     */
    @PostMapping("/append-text")
    public Result<Boolean> appendText(@Valid @RequestBody SftpAppendTextRequest request) {
        log.info("SftpDocumentController: 收到追加写入文件请求 [remotePath={}]", request.getRemotePath());
        sftpScenarioService.append(request.getRemotePath(), request.getContent().getBytes(StandardCharsets.UTF_8));
        return Result.success(true);
    }

    /**
     * 跨目录移动远端文件
     *
     * @param request 移动请求载荷
     * @return 成功状态
     */
    @PostMapping("/copy-move")
    public Result<Boolean> copyMove(@Valid @RequestBody SftpCopyMoveRequest request) {
        log.info("SftpDocumentController: 收到移动文件请求 [sourcePath={} -> targetPath={}]",
                request.getSourcePath(), request.getTargetPath());
        sftpScenarioService.move(request.getSourcePath(), request.getTargetPath());
        return Result.success(true);
    }

    /**
     * 原地原子重命名远端文件或目录
     *
     * @param request 重命名请求载荷
     * @return 成功状态
     */
    @PostMapping("/rename")
    public Result<Boolean> rename(@Valid @RequestBody SftpRenameRequest request) {
        log.info("SftpDocumentController: 收到重命名文件请求 [oldPath={} -> newPath={}]",
                request.getOldPath(), request.getNewPath());
        sftpScenarioService.rename(request.getOldPath(), request.getNewPath());
        return Result.success(true);
    }

    /**
     * 查询远端文件或目录的详细元数据
     *
     * @param remotePath 远端路径
     * @return 元数据 VO
     */
    @GetMapping("/info")
    public Result<SftpFileInfoVO> getInfo(@RequestParam("remotePath") @NotBlank(message = "远程路径不能为空") String remotePath) {
        log.info("SftpDocumentController: 收到查询文件元数据请求 [remotePath={}]", remotePath);
        SftpFileInfo info = sftpScenarioService.stat(remotePath);
        return Result.success(SftpFileInfoVO.from(info));
    }

    /**
     * 检查远端文件或目录是否存在
     *
     * @param remotePath 远端路径
     * @return true 若存在
     */
    @GetMapping("/exists")
    public Result<Boolean> exists(@RequestParam("remotePath") @NotBlank(message = "远程路径不能为空") String remotePath) {
        log.info("SftpDocumentController: 收到检查路径是否存在请求 [remotePath={}]", remotePath);
        boolean exist = sftpScenarioService.exists(remotePath);
        return Result.success(exist);
    }

    /**
     * 单文件确定性物理删除
     *
     * @param request 删除请求载荷
     * @return true 若删除成功
     */
    @PostMapping("/delete")
    public Result<Boolean> deleteFile(@Valid @RequestBody SftpPathRequest request) {
        log.info("SftpDocumentController: 收到删除单文件请求 [remotePath={}]", request.getRemotePath());
        boolean deleted = sftpScenarioService.delete(request.getRemotePath());
        return Result.success(deleted);
    }
}
