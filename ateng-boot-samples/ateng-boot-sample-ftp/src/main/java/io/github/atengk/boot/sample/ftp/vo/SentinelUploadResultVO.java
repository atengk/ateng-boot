package io.github.atengk.boot.sample.ftp.vo;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serial;
import java.io.Serializable;

/**
 * 对账信标伴随上传结果 VO
 *
 * @author Ateng
 * @since 2026-11-17
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class SentinelUploadResultVO implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    /**
     * 远程主文件路径
     */
    private String remotePath;

    /**
     * 远程信标文件路径
     */
    private String sentinelPath;

    /**
     * 主文件字节长度
     */
    private Long fileSize;

    /**
     * 已写入的信标文件清单正文
     */
    private String sentinelContent;
}
