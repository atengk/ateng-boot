package io.github.atengk.boot.ftp.listener;

/**
 * FTP 流式传输进度监听函数式接口
 * <p>
 * 纯原生函数式契约，完全解耦底层 Apache Commons Net 专有事件监听类，
 * 支持在流式上传与下载过程中以 Lambda 表达式透明挂载，
 * 接收已传输字节量、总字节大小以及格式化百分比进度。
 * </p>
 *
 * @author Ateng
 * @since 2026-10-30
 */
@FunctionalInterface
public interface FtpProgressListener {

    /**
     * 传输进度实时回调
     *
     * @param bytesTransferred 已成功传输的字节累计总数
     * @param totalBytes       目标文件总字节大小（若未知则为 -1）
     * @param percentage       当前传输完成进度百分比（取值范围 0.0 ~ 100.0；若总大小未知则为 -1.0）
     */
    void onProgress(long bytesTransferred, long totalBytes, double percentage);
}
