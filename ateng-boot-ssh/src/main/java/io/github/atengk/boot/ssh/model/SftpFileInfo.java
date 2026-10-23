package io.github.atengk.boot.ssh.model;

import lombok.Builder;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.ToString;
import org.apache.sshd.sftp.client.SftpClient;

import java.time.Instant;
import java.time.temporal.ChronoUnit;

/**
 * SFTP 远程文件与目录只读元数据载荷
 *
 * @author Ateng
 * @since 2026-10-23
 */
@Getter
@ToString
@EqualsAndHashCode
@Builder
public final class SftpFileInfo {

    /**
     * 文件或目录名称（不含父路径）
     */
    private final String filename;

    /**
     * 规范化后的绝对完整远程路径
     */
    private final String path;

    /**
     * 文件字节大小
     */
    private final long size;

    /**
     * 最后修改时间戳
     */
    private final Instant modifiedTime;

    /**
     * 是否为目录
     */
    private final boolean directory;

    /**
     * 是否为普通文件
     */
    private final boolean regularFile;

    /**
     * 是否为符号链接
     */
    private final boolean symbolicLink;

    /**
     * POSIX 权限掩码数值（如 0644, 0755）
     */
    private final int permissions;

    /**
     * 人类可读权限字符串（如 "rw-r--r--"）
     */
    private final String permissionsString;

    /**
     * 将 MINA SSHD 属性与路径转换为强类型 SftpFileInfo 载荷
     *
     * @param fullPath   完整远程路径
     * @param attributes SSHD SFTP 属性
     * @return 元数据载荷
     */
    public static SftpFileInfo fromAttributes(String fullPath, SftpClient.Attributes attributes) {
        String filename = extractFilename(fullPath);
        long size = attributes != null ? attributes.getSize() : 0L;
        Instant modifiedTime = null;
        if (attributes != null && attributes.getModifyTime() != null) {
            modifiedTime = attributes.getModifyTime().toInstant().truncatedTo(ChronoUnit.SECONDS);
        }
        boolean isDir = attributes != null && attributes.isDirectory();
        boolean isReg = attributes != null && attributes.isRegularFile();
        boolean isSym = attributes != null && attributes.isSymbolicLink();
        int perms = attributes != null ? attributes.getPermissions() : 0;
        String permsString = formatPermissions(perms);

        return SftpFileInfo.builder()
                .filename(filename)
                .path(fullPath)
                .size(size)
                .modifiedTime(modifiedTime)
                .directory(isDir)
                .regularFile(isReg)
                .symbolicLink(isSym)
                .permissions(perms)
                .permissionsString(permsString)
                .build();
    }

    private static String extractFilename(String fullPath) {
        if (fullPath == null || fullPath.isBlank() || "/".equals(fullPath)) {
            return "/";
        }
        int lastSlash = fullPath.lastIndexOf('/');
        if (lastSlash >= 0 && lastSlash < fullPath.length() - 1) {
            return fullPath.substring(lastSlash + 1);
        }
        return fullPath;
    }

    /**
     * 格式化 POSIX 权限位为 9 字符标准字符串（如 rwxr-xr-x）
     *
     * @param perms 权限位
     * @return 权限字符串
     */
    public static String formatPermissions(int perms) {
        StringBuilder sb = new StringBuilder(9);
        sb.append((perms & 0400) != 0 ? 'r' : '-');
        sb.append((perms & 0200) != 0 ? 'w' : '-');
        sb.append((perms & 0100) != 0 ? 'x' : '-');
        sb.append((perms & 0040) != 0 ? 'r' : '-');
        sb.append((perms & 0020) != 0 ? 'w' : '-');
        sb.append((perms & 0010) != 0 ? 'x' : '-');
        sb.append((perms & 0004) != 0 ? 'r' : '-');
        sb.append((perms & 0002) != 0 ? 'w' : '-');
        sb.append((perms & 0001) != 0 ? 'x' : '-');
        return sb.toString();
    }
}
