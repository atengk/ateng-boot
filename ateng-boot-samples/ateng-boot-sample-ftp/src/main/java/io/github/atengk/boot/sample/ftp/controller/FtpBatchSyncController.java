package io.github.atengk.boot.sample.ftp.controller;

import io.github.atengk.boot.core.model.Result;
import io.github.atengk.boot.ftp.model.BatchTransferResult;
import io.github.atengk.boot.ftp.model.FtpFileInfo;
import io.github.atengk.boot.sample.ftp.dto.DirectorySyncRequest;
import io.github.atengk.boot.sample.ftp.service.SettlementBatchSyncScenarioService;
import io.github.atengk.boot.sample.ftp.vo.BatchTransferResultVO;
import io.github.atengk.boot.sample.ftp.vo.TransferFailureItemVO;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.util.StringUtils;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.io.File;
import java.util.Collections;
import java.util.List;
import java.util.function.Predicate;

/**
 * 金融对账全目录递归镜像同步与批处理控制器
 * <p>
 * 提供多层级目录镜像上传/下载、宿主隐性系统文件过滤、Fail-Safe 容错批处理与聚合回执端点。
 * </p>
 *
 * @author Ateng
 * @since 2026-11-10
 */
@RestController
@RequestMapping("/sample/ftp/sync")
@RequiredArgsConstructor
@Validated
@Slf4j
public class FtpBatchSyncController {

    private final SettlementBatchSyncScenarioService settlementBatchSyncService;

    /**
     * 批量递归镜像同步本地目录至远程 FTP
     *
     * @param request 目录同步请求
     * @return 批量传输强类型聚合回执
     */
    @PostMapping("/upload-directory")
    public Result<BatchTransferResultVO> uploadDirectory(@Valid @RequestBody DirectorySyncRequest request) {
        File localDir = new File(request.getLocalDir());
        BatchTransferResult result;

        if (StringUtils.hasText(request.getFilterExtension())) {
            String ext = request.getFilterExtension();
            Predicate<File> filter = f -> f.getName().endsWith(ext);
            result = settlementBatchSyncService.syncSettlementToRemote(localDir, request.getRemoteDir(), filter, request.isFailFast());
        } else {
            result = settlementBatchSyncService.syncSettlementToRemote(localDir, request.getRemoteDir(), null, request.isFailFast());
        }

        return Result.success(toVO(result));
    }

    /**
     * 批量递归镜像下载远程 FTP 目录至本地
     *
     * @param request 目录同步请求
     * @return 批量传输强类型聚合回执
     */
    @PostMapping("/download-directory")
    public Result<BatchTransferResultVO> downloadDirectory(@Valid @RequestBody DirectorySyncRequest request) {
        File localDir = new File(request.getLocalDir());
        BatchTransferResult result;

        if (StringUtils.hasText(request.getFilterExtension())) {
            String ext = request.getFilterExtension();
            Predicate<FtpFileInfo> filter = f -> f.getName().endsWith(ext);
            result = settlementBatchSyncService.syncSettlementFromRemote(request.getRemoteDir(), localDir, filter, request.isFailFast());
        } else {
            result = settlementBatchSyncService.syncSettlementFromRemote(request.getRemoteDir(), localDir, null, request.isFailFast());
        }

        return Result.success(toVO(result));
    }

    private BatchTransferResultVO toVO(BatchTransferResult result) {
        if (result == null) {
            return null;
        }

        List<TransferFailureItemVO> failures = result.getFailures() != null
                ? result.getFailures().stream()
                .map(f -> TransferFailureItemVO.builder()
                        .path(f.getPath())
                        .errorMessage(f.getErrorMessage())
                        .build())
                .toList()
                : Collections.emptyList();

        return BatchTransferResultVO.builder()
                .totalFiles(result.getTotalFiles())
                .successCount(result.getSuccessCount())
                .failureCount(result.getFailureCount())
                .skippedCount(result.getSkippedCount())
                .totalBytesTransferred(result.getTransferredBytes())
                .durationMs(result.getDuration() != null ? result.getDuration().toMillis() : 0L)
                .allSuccess(result.isAllSuccess())
                .failures(failures)
                .build();
    }
}
