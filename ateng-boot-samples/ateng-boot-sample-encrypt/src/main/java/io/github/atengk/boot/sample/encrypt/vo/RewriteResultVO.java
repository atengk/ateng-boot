package io.github.atengk.boot.sample.encrypt.vo;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * 配置文件就地加解密回写结果展示对象
 *
 * @author Ateng
 * @since 2026-11-11
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class RewriteResultVO {

    /**
     * 处理前原始配置文件文本
     */
    private String originalContent;

    /**
     * 就地重写后的配置文件文本
     */
    private String rewrittenContent;

    /**
     * 实际加密或解密替换的字段/行数
     */
    private int modifiedCount;

    /**
     * 是否成功生成 .bak 备份文件
     */
    private boolean backupCreated;

    /**
     * 配置文件类型（yml 或 properties）
     */
    private String fileType;

    /**
     * 变更差异行预览（简单 Diff）
     */
    private List<String> diffLines;
}
