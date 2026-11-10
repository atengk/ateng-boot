package io.github.atengk.boot.sample.ssh.controller;

import io.github.atengk.boot.core.model.Result;
import io.github.atengk.boot.sample.ssh.dto.SftpDeleteDirectoryRequest;
import io.github.atengk.boot.sample.ssh.dto.SftpDirectorySyncRequest;
import io.github.atengk.boot.sample.ssh.dto.SftpLargeTransferRequest;
import io.github.atengk.boot.sample.ssh.service.SftpScenarioService;
import io.github.atengk.boot.sample.ssh.vo.SftpSyncResultVO;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.io.File;
import java.time.Duration;
import java.util.Arrays;
import java.util.concurrent.atomic.AtomicLong;

/**
 * SFTP 整包目录树同步与批处理传输控制器
 * <p>
 * 提供目录树整包双向镜像上传与下载同步、DFS 深度优先非空目录递归清空删除、
 * 以及大文件带节流防抖进度流式上传等核心接口。
 * </p>
 *
 * @author Ateng
 * @since 2026-11-10
 */
@Slf4j
@Validated
@RestController
@RequestMapping("/sample/ssh/sftp-sync")
@RequiredArgsConstructor
public class SftpSyncController {

    private final SftpScenarioService sftpScenarioService;

    /**
     * 整包递归镜像同步本地目录至远程 SFTP 服务端
     *
     * @param request 目录同步请求载荷
     * @return 目录同步回执
     */
    @PostMapping("/upload-dir")
    public Result<SftpSyncResultVO> uploadDirectory(@Valid @RequestBody SftpDirectorySyncRequest request) {
        log.info("SftpSyncController: 收到目录树整包上传请求 [localDir={} -> remoteDir={}, failFast={}]",
                request.getLocalDir(), request.getRemoteDir(), request.getFailFast());

        long start = System.currentTimeMillis();
        File localFolder = new File(request.getLocalDir());
        sftpScenarioService.syncUploadDirectory(localFolder, request.getRemoteDir());
        long cost = System.currentTimeMillis() - start;

        int fileCount = localFolder.exists() && localFolder.listFiles() != null ? localFolder.listFiles().length : 0;
        SftpSyncResultVO vo = SftpSyncResultVO.builder()
                .localDir(request.getLocalDir())
                .remoteDir(request.getRemoteDir())
                .syncCount(fileCount)
                .durationMs(cost)
                .success(true)
                .message("整包目录树镜像上传同步成功")
                .build();

        return Result.success(vo);
    }

    /**
     * 整包递归镜像同步远程 SFTP 目录至本地磁盘
     *
     * @param request 目录同步请求载荷
     * @return 目录同步回执
     */
    @PostMapping("/download-dir")
    public Result<SftpSyncResultVO> downloadDirectory(@Valid @RequestBody SftpDirectorySyncRequest request) {
        log.info("SftpSyncController: 收到目录树整包下载请求 [remoteDir={} -> localDir={}, failFast={}]",
                request.getRemoteDir(), request.getLocalDir(), request.getFailFast());

        long start = System.currentTimeMillis();
        File localFolder = new File(request.getLocalDir());
        sftpScenarioService.syncDownloadDirectory(request.getRemoteDir(), localFolder);
        long cost = System.currentTimeMillis() - start;

        int fileCount = localFolder.exists() && localFolder.listFiles() != null ? localFolder.listFiles().length : 0;
        SftpSyncResultVO vo = SftpSyncResultVO.builder()
                .localDir(request.getLocalDir())
                .remoteDir(request.getRemoteDir())
                .syncCount(fileCount)
                .durationMs(cost)
                .success(true)
                .message("整包目录树镜像下载同步成功")
                .build();

        return Result.success(vo);
    }

    /**
     * DFS 深度优先递归清空删除远程非空目录树
     *
     * @param request 目录删除请求载荷
     * @return 是否成功删除
     */
    @PostMapping("/delete-dir")
    public Result<Boolean> deleteDirectory(@Valid @RequestBody SftpDeleteDirectoryRequest request) {
        log.info("SftpSyncController: 收到删除目录请求 [remotePath={}, recursive={}]",
                request.getRemotePath(), request.getRecursive());
        boolean deleted;
        if (Boolean.FALSE.equals(request.getRecursive())) {
            deleted = sftpScenarioService.delete(request.getRemotePath());
        } else {
            deleted = sftpScenarioService.deleteRecursively(request.getRemotePath());
        }
        return Result.success(deleted);
    }

    /**
     * 模拟大文件带流式进度防抖监听上传（演示 100ms 最小间隔节流与防日志风暴）
     *
     * @param request 大文件传输请求载荷
     * @return 传输结果提示
     */
    @PostMapping("/upload-large")
    public Result<String> uploadLargeFile(@Valid @RequestBody SftpLargeTransferRequest request) {
        int sizeBytes = request.getSizeKb() * 1024;
        log.info("SftpSyncController: 收到大文件模拟节流上传请求 [remotePath={}, size={} bytes ({} KB)]",
                request.getRemotePath(), sizeBytes, request.getSizeKb());

        byte[] payload = new byte[sizeBytes];
        Arrays.fill(payload, (byte) 'A');

        AtomicLong callbackCount = new AtomicLong(0);
        sftpScenarioService.uploadWithThrottledProgress(
                request.getRemotePath(),
                payload,
                Duration.ofMillis(100),
                (transferred, total) -> callbackCount.incrementAndGet()
        );

        return Result.success("大文件节流上传成功: " + request.getRemotePath()
                + " [size=" + request.getSizeKb() + "KB, 触发回调次数=" + callbackCount.get() + "]");
    }
}
