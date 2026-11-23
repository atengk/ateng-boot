package io.github.atengk.boot.sample.ftp.vo;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serial;
import java.io.Serializable;

/**
 * 批量传输单项失败明细 VO
 *
 * @author Ateng
 * @since 2026-11-10
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class TransferFailureItemVO implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    /**
     * 发生失败的文件或目录路径
     */
    private String path;

    /**
     * 异常错误信息
     */
    private String errorMessage;
}
