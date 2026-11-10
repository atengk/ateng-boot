package io.github.atengk.boot.sample.ftp.vo;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serial;
import java.io.Serializable;

/**
 * 流式传输进度追踪 VO
 *
 * @author Ateng
 * @since 2026-11-10
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class TransferProgressVO implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    /**
     * 传输文件路径
     */
    private String path;

    /**
     * 已传输总字节量
     */
    private Long bytesTransferred;

    /**
     * 预计传输总字节量（若流未知则为 -1）
     */
    private Long totalBytes;

    /**
     * 当前完成百分比（0.0 ~ 100.0）
     */
    private Double percentage;

    /**
     * 是否已完全完成传输
     */
    private Boolean completed;
}
