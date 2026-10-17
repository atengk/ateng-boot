package io.github.atengk.boot.sample.excel.provider;

import io.github.atengk.boot.excel.spi.DictProvider;
import org.springframework.stereotype.Component;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 示例业务字典 SPI 提供者
 * 用于在 Samples 中验证 @ExcelDict 双向自动转义机制
 *
 * @author Ateng
 * @since 2026-10-17
 */
@Component
public class SampleDictProvider implements DictProvider {

    private final Map<String, Map<String, String>> dictMap = new LinkedHashMap<>();

    /**
     * 默认构造方法，预置示例用户状态字典数据
     */
    public SampleDictProvider() {
        Map<String, String> statusMap = new LinkedHashMap<>();
        statusMap.put("0", "正常");
        statusMap.put("1", "停用");
        dictMap.put("user_status", statusMap);
    }

    /**
     * 将业务字典代码转换为展示标签
     *
     * @param dictType 字典分类类型
     * @param code     业务字典代码
     * @return 对应的中文描述标签，未匹配时返回代码原始字符串
     */
    @Override
    public String toLabel(String dictType, Object code) {
        if (code == null) {
            return "";
        }
        String codeStr = String.valueOf(code);
        Map<String, String> items = dictMap.get(dictType);
        if (items == null) {
            return codeStr;
        }
        return items.getOrDefault(codeStr, codeStr);
    }

    /**
     * 将展示标签反向解析为业务代码
     *
     * @param dictType 字典分类类型
     * @param label    中文描述标签
     * @return 对应的业务代码，未匹配时返回原始标签
     */
    @Override
    public String toCode(String dictType, String label) {
        if (label == null || label.isBlank()) {
            return "";
        }
        Map<String, String> items = dictMap.get(dictType);
        if (items == null) {
            return label;
        }
        for (Map.Entry<String, String> entry : items.entrySet()) {
            if (entry.getValue().equalsIgnoreCase(label.trim())) {
                return entry.getKey();
            }
        }
        return label;
    }
}
