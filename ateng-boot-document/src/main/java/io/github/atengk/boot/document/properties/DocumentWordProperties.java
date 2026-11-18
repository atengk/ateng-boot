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

    /**
     * 是否在 Word 模板渲染阶段开启 SpringEL 表达式解析，默认关闭以防止高危任意代码注入
     */
    private boolean springElEnabled = false;

    /**
     * SpringEL 解析失败或变量缺失时是否严格抛异常中断，默认关闭
     */
    private boolean springElStrict = false;
}
