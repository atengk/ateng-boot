package io.github.atengk.boot.sample.http.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;

/**
 * 流式 Multipart 分片上传结果视图对象
 *
 * @author Ateng
 * @since 2026-11-14
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class FileUploadVO implements Serializable {

    private static final long serialVersionUID = 1L;

    /**
     * 上传原始文件名
     */
    private String filename;

    /**
     * 接收端实际读取的文件字节数
     */
    private long size;

    /**
     * 随分片附加的业务分类表单字段
     */
    private String bizType;

    /**
     * 文件分片媒体类型
     */
    private String contentType;

    /**
     * 验证传输层未泄漏任何内部控制头
     */
    private boolean internalHeaderClean;
}
