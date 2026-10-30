package io.github.atengk.boot.sample.ftp.service;

import io.github.atengk.boot.ftp.listener.FtpProgressListener;
import io.github.atengk.boot.ftp.template.FtpTemplate;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.io.File;
import java.io.InputStream;
import java.io.OutputStream;

/**
 * 业务场景三：大文件流式传输与平滑进度监听服务组件
 * <p>
 * 演示并实测超大文件（数十 MB 至数 GB）的稳定流式吞吐：
 * 1. 上传大文件并挂载 {@link FtpProgressListener} 实时监控传输进度；
 * 2. 下载大文件并挂载进度监听器实时显示百分比与字节速率；
 * 3. 验证框架内置平滑节流引擎（1% 步长或 200ms 防抖 + 100% 满额终态保底），消除日志与回调风暴。
 * </p>
 *
 * @author Ateng
 * @since 2026-10-30
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class LargeFileTransferScenarioService {

    private final FtpTemplate ftpTemplate;

    /**
     * 上传大文件到远程 FTP 并挂载进度监听器
     *
     * @param remotePath       远程目标路径
     * @param localFile        本地待上传文件
     * @param progressListener 传输进度监听回调（为 null 时使用控制台标准心跳输出）
     * @return 上传成功返回 true
     */
    public boolean uploadLargeFile(String remotePath, File localFile, FtpProgressListener progressListener) {
        log.info("开始大文件流式上传: {} -> {}, 文件大小: {} bytes",
                localFile.getAbsolutePath(), remotePath, localFile.length());

        FtpProgressListener listener = progressListener != null ? progressListener : (bytesTransferred, totalBytes, percentage) -> {
            log.info("[UPLOAD-PROGRESS] 目标: {}, 进度: {}/{} bytes ({})",
                    remotePath, bytesTransferred, totalBytes, String.format("%.2f%%", percentage));
        };

        return ftpTemplate.upload(remotePath, localFile, listener);
    }

    /**
     * 上传大文件输入流并挂载进度监听器
     *
     * @param remotePath       远程目标路径
     * @param in               输入流
     * @param totalBytes       数据总字节量
     * @param progressListener 传输进度监听回调
     * @return 上传成功返回 true
     */
    public boolean uploadLargeStream(String remotePath, InputStream in, long totalBytes, FtpProgressListener progressListener) {
        log.info("开始大文件数据流上传: {}, 预期大小: {} bytes", remotePath, totalBytes);
        return ftpTemplate.upload(remotePath, in, totalBytes, progressListener);
    }

    /**
     * 下载远程大文件到本地磁盘并挂载进度监听器
     *
     * @param remotePath       远程文件路径
     * @param localFile        本地目标文件
     * @param progressListener 传输进度监听回调（为 null 时使用控制台标准心跳输出）
     * @return 下载成功返回 true
     */
    public boolean downloadLargeFile(String remotePath, File localFile, FtpProgressListener progressListener) {
        log.info("开始大文件流式下载: {} -> {}", remotePath, localFile.getAbsolutePath());

        FtpProgressListener listener = progressListener != null ? progressListener : (bytesTransferred, totalBytes, percentage) -> {
            log.info("[DOWNLOAD-PROGRESS] 来源: {}, 进度: {}/{} bytes ({})",
                    remotePath, bytesTransferred, totalBytes, String.format("%.2f%%", percentage));
        };

        return ftpTemplate.download(remotePath, localFile, listener);
    }

    /**
     * 下载远程大文件到输出流并挂载进度监听器
     *
     * @param remotePath       远程文件路径
     * @param out              目标输出流
     * @param progressListener 传输进度监听回调
     * @return 下载成功返回 true
     */
    public boolean downloadLargeStream(String remotePath, OutputStream out, FtpProgressListener progressListener) {
        log.info("开始大文件数据流下载: {}", remotePath);
        return ftpTemplate.download(remotePath, out, progressListener);
    }
}
