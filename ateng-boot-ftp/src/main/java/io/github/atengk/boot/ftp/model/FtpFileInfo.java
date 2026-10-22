package io.github.atengk.boot.ftp.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;
import java.time.Instant;

/**
 * FTP 远程文件与目录强类型元数据契约
 *
 * @author Ateng
 * @since 2026-10-22
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class FtpFileInfo implements Serializable {

    private static final long serialVersionUID = 1L;

    /**
     * 文件或目录名称（如 bill.csv）
     */
    private String name;

    /**
     * 文件完整路径（如 /data/bill.csv）
     */
    private String path;

    /**
     * 文件大小（字节数）
     */
    private long size;

    /**
     * 是否为目录
     */
    private boolean directory;

    /**
     * 是否为普通文件
     */
    private boolean file;

    /**
     * 最后修改时间戳 (UTC Instant)
     */
    private Instant lastModified;

    public boolean isDirectory() {
        return directory;
    }

    public boolean isFile() {
        return file;
    }

    public static class FtpFileInfoBuilder {
        public FtpFileInfoBuilder isDirectory(boolean isDirectory) {
            this.directory = isDirectory;
            return this;
        }

        public FtpFileInfoBuilder isFile(boolean isFile) {
            this.file = isFile;
            return this;
        }
    }
}
