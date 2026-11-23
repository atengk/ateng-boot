package io.github.atengk.boot.sample.ssh.vo;

import io.github.atengk.boot.ssh.model.SftpFileInfo;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serial;
import java.io.Serializable;
import java.time.Instant;

/**
 * 远程 SFTP 文件/目录只读元数据 VO
 *
 * @author Ateng
 * @since 2026-11-10
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class SftpFileInfoVO implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    /**
     * 文件或目录基名
     */
    private String filename;

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
    private Instant modifiedTime;

    /**
     * 是否为目录
     */
    private Boolean directory;

    /**
     * 是否为常规文件
     */
    private Boolean regularFile;

    /**
     * 是否为符号链接
     */
    private Boolean symbolicLink;

    /**
     * POSIX 权限掩码数值（如 0644, 0755）
     */
    private Integer permissions;

    /**
     * POSIX 权限字符（如 "rw-r--r--"）
     */
    private String permissionsString;

    /**
     * 从底层实体转换为业务 VO
     *
     * @param info 底层元数据载荷
     * @return 业务 VO
     */
    public static SftpFileInfoVO from(SftpFileInfo info) {
        if (info == null) {
            return null;
        }
        return SftpFileInfoVO.builder()
                .filename(info.getFilename())
                .path(info.getPath())
                .size(info.getSize())
                .modifiedTime(info.getModifiedTime())
                .directory(info.isDirectory())
                .regularFile(info.isRegularFile())
                .symbolicLink(info.isSymbolicLink())
                .permissions(info.getPermissions())
                .permissionsString(info.getPermissionsString())
                .build();
    }
}
