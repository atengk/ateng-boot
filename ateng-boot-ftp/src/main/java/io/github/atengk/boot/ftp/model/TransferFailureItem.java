package io.github.atengk.boot.ftp.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;
import java.time.Instant;

/**
 * FTP 批量传输单文件失败明细记录
 *
 * @author Ateng
 * @since 2026-10-30
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class TransferFailureItem implements Serializable {

    private static final long serialVersionUID = 1L;

    /**
     * 传输失败的文件路径（本地或远程路径）
     */
    private String path;

    /**
     * 异常简要信息
     */
    private String errorMessage;

    /**
     * 异常类型全类名
     */
    private String exceptionType;

    /**
     * 失败发生时间戳 (UTC Instant)
     */
    private Instant failedAt;

    /**
     * 便捷静态构造工厂方法
     *
     * @param path      传输失败的文件路径
     * @param throwable 捕获的异常对象
     * @return 传输失败明细记录
     */
    public static TransferFailureItem of(String path, Throwable throwable) {
        return TransferFailureItem.builder()
                .path(path)
                .errorMessage(throwable != null ? throwable.getMessage() : "Unknown error")
                .exceptionType(throwable != null ? throwable.getClass().getName() : null)
                .failedAt(Instant.now())
                .build();
    }
}
