package io.github.atengk.boot.document.properties;

import lombok.Data;

/**
 * Word 文档处理专属配置属性
 *
 * @author Ateng
 * @since 2026-10-23
 */
@Data
public class DocumentWordProperties {

    /**
     * Word 模板文件默认基准路径前缀
     */
    private String templateBaseDir = "classpath:/templates/word/";
}
