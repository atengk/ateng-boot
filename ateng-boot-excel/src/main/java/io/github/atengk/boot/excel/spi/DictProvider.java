package io.github.atengk.boot.excel.spi;

/**
 * Excel 字典双向转义 SPI 接口
 * <p>
 * 业务工程实现该接口并注入为 Spring Bean 即可无缝接入已有的系统字典、缓存或数据库字典体系。
 *
 * @author Ateng
 * @since 2026-10-16
 */
public interface DictProvider {

    /**
     * 根据字典类型和字典编码获取展示标签 (导出时调用)
     *
     * @param dictType 字典类型 (例如 "sys_user_status")
     * @param code     字典编码 (例如 "1"、1、"MALE")
     * @return 字典展示标签 (例如 "启用"、"男")；若无匹配可返回 null
     */
    String toLabel(String dictType, Object code);

    /**
     * 根据字典类型和展示标签反查字典编码 (导入时调用)
     *
     * @param dictType 字典类型 (例如 "sys_user_status")
     * @param label    展示标签 (例如 "启用"、"男")
     * @return 字典编码字符串 (例如 "1"、"MALE")；若无匹配可返回 null
     */
    String toCode(String dictType, String label);
}
