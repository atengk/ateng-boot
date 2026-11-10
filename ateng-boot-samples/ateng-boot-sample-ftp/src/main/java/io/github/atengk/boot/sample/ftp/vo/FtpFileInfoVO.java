package io.github.atengk.boot.sample.ftp.vo;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serial;
import java.io.Serializable;
import java.time.Instant;

/**
 * 远程 FTP 文件/目录强类型元数据 VO
 *
 * @author Ateng
 * @since 2026-11-10
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class FtpFileInfoVO implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    /**
     * 文件或目录基名
     */
    private String name;

    /**
     * 规范化绝对路径
     */
    private String path;

    /**
     * 文件字节大小（目录通常为 0）
     */
    private Long size;

    /**
     * 服务端最后修改时间戳
     */
    private Instant lastModified;

    /**
     * 是否为目录
     */
    private Boolean directory;

    /**
     * 是否为常规文件
     */
    private Boolean file;
}
