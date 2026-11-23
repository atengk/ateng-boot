package io.github.atengk.boot.sample.http.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;

/**
 * 文件流式下载验证响应视图对象
 *
 * @author Ateng
 * @since 2026-11-09
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class FileDownloadVO implements Serializable {

    private static final long serialVersionUID = 1L;

    /**
     * 文件总字节大小
     */
    private long size;

    /**
     * 文件名称
     */
    private String filename;

    /**
     * 文件内容预览（前缀或纯文本截取）
     */
    private String previewContent;
}
