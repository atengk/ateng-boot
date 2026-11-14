package io.github.atengk.boot.storage.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.InputStream;
import java.util.Map;

/**
 * 统一文件上传请求对象
 * 收敛路径、流载荷、MIME 类型、用户自定义元数据、访问权限及覆盖控制参数
 *
 * @author Ateng
 * @since 2026-11-14
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class StorageUploadRequest {

    /**
     * 目标对象相对路径（如 2026/11/14/avatar.png）
     */
    private String path;

    /**
     * 对象内容输入流
     */
    private InputStream inputStream;

    /**
     * MIME 内容类型（为空时由驱动自动推导）
     */
    private String contentType;

    /**
     * 数据流字节大小（未知时传 {@code -1}）
     */
    @Builder.Default
    private long size = -1L;

    /**
     * 用户自定义元数据键值对
     */
    private Map<String, String> userMetadata;

    /**
     * 是否赋予公共读取权限
     */
    private boolean publicRead;

    /**
     * 当目标对象已存在时是否允许覆盖写入（默认 {@code true}）
     */
    @Builder.Default
    private boolean overwrite = true;

}
