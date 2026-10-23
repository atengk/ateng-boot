package io.github.atengk.boot.ssh.properties;

import lombok.Data;
import org.springframework.util.unit.DataSize;

import java.time.Duration;

/**
 * SFTP 文件传输子系统核心配置属性
 *
 * @author Ateng
 * @since 2026-10-23
 */
@Data
public class SftpProperties {

    /**
     * 流式传输数据块缓冲区大小（默认 32KB）
     */
    private DataSize bufferSize = DataSize.ofKilobytes(32);

    /**
     * 单文件传输读写超时阈值（默认 10 分钟）
     */
    private Duration transferTimeout = Duration.ofMinutes(10);

    /**
     * 是否启用原子上传机制（先写临时文件后原子重命名覆盖，防止脏读）
     */
    private boolean atomicUploadEnabled = true;

    /**
     * 原子上传临时文件后缀名
     */
    private String atomicUploadSuffix = ".uploading.tmp";
}
