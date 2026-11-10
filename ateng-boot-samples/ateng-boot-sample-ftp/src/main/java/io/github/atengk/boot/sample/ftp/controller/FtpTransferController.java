package io.github.atengk.boot.sample.ftp.controller;

import io.github.atengk.boot.core.model.Result;
import io.github.atengk.boot.ftp.template.FtpTemplate;
import io.github.atengk.boot.sample.ftp.dto.LargeFileTransferRequest;
import io.github.atengk.boot.sample.ftp.service.LargeFileTransferScenarioService;
import io.github.atengk.boot.sample.ftp.vo.TransferProgressVO;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.util.StringUtils;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.util.Arrays;
import java.util.concurrent.atomic.AtomicReference;

/**
 * 大文件流式传输与平滑进度监听控制器
 * <p>
 * 提供流式进度追踪上传/下载、1% 步长/200ms 防抖节流引擎、100% 满额终态保底与 50MB 堆内存防爆端点。
 * </p>
 *
 * @author Ateng
 * @since 2026-11-10
 */
@RestController
@RequestMapping("/sample/ftp/transfer")
@RequiredArgsConstructor
@Validated
@Slf4j
public class FtpTransferController {

    private final LargeFileTransferScenarioService largeFileTransferService;
    private final FtpTemplate ftpTemplate;

    /**
     * 流式大文件上传并挂载平滑防抖进度监听
     *
     * @param request 大文件传输请求
     * @return 最终传输进度追踪回执
     */
    @PostMapping("/upload-large")
    public Result<TransferProgressVO> uploadLargeFile(@Valid @RequestBody LargeFileTransferRequest request) throws IOException {
        int sizeKb = request.getSizeKb() != null && request.getSizeKb() > 0 ? request.getSizeKb() : 128;
        File tempFile = File.createTempFile("sample-large-upload-", ".bin");

        try {
            // 快速生成指定容量的测试字节文件
            byte[] chunk = new byte[1024];
            Arrays.fill(chunk, (byte) 0x5A);
            try (FileOutputStream fos = new FileOutputStream(tempFile)) {
                for (int i = 0; i < sizeKb; i++) {
                    fos.write(chunk);
                }
            }

            AtomicReference<TransferProgressVO> progressRef = new AtomicReference<>(TransferProgressVO.builder()
                    .path(request.getRemotePath())
                    .bytesTransferred(0L)
                    .totalBytes((long) sizeKb * 1024)
                    .percentage(0.0)
                    .completed(false)
                    .build());

            largeFileTransferService.uploadLargeFile(request.getRemotePath(), tempFile, (bytesTransferred, totalBytes, percentage) -> {
                progressRef.set(TransferProgressVO.builder()
                        .path(request.getRemotePath())
                        .bytesTransferred(bytesTransferred)
                        .totalBytes(totalBytes)
                        .percentage(percentage)
                        .completed(percentage >= 100.0)
                        .build());
            });

            return Result.success(progressRef.get());
        } finally {
            if (tempFile.exists()) {
                tempFile.delete();
            }
        }
    }

    /**
     * 流式大文件下载并挂载平滑防抖进度监听
     *
     * @param request 大文件传输请求
     * @return 最终传输进度追踪回执
     */
    @PostMapping("/download-large")
    public Result<TransferProgressVO> downloadLargeFile(@Valid @RequestBody LargeFileTransferRequest request) throws IOException {
        File targetFile = StringUtils.hasText(request.getLocalPath())
                ? new File(request.getLocalPath())
                : File.createTempFile("sample-large-download-", ".bin");

        try {
            AtomicReference<TransferProgressVO> progressRef = new AtomicReference<>(TransferProgressVO.builder()
                    .path(request.getRemotePath())
                    .bytesTransferred(0L)
                    .totalBytes(-1L)
                    .percentage(0.0)
                    .completed(false)
                    .build());

            largeFileTransferService.downloadLargeFile(request.getRemotePath(), targetFile, (bytesTransferred, totalBytes, percentage) -> {
                progressRef.set(TransferProgressVO.builder()
                        .path(request.getRemotePath())
                        .bytesTransferred(bytesTransferred)
                        .totalBytes(totalBytes)
                        .percentage(percentage)
                        .completed(percentage >= 100.0)
                        .build());
            });

            return Result.success(progressRef.get());
        } finally {
            if (!StringUtils.hasText(request.getLocalPath()) && targetFile.exists()) {
                targetFile.delete();
            }
        }
    }

    /**
     * 内存字节下载演示（受 50MB max-download-bytes 堆内存防爆阈值守护）
     *
     * @param remotePath 远程文件路径
     * @return 下载到的文件字节总长度
     */
    @GetMapping("/download-safe")
    public Result<Integer> downloadSafe(@RequestParam("remotePath") String remotePath) {
        byte[] bytes = ftpTemplate.downloadToBytes(remotePath);
        return Result.success(bytes != null ? bytes.length : 0);
    }
}
