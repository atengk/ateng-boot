package io.github.atengk.boot.excel.handler;

import io.github.atengk.boot.excel.annotation.ExcelFormulaAllowed;
import io.github.atengk.boot.excel.util.ExcelSecurityUtil;
import io.github.atengk.boot.excel.util.ExcelUtil;
import org.apache.fesod.sheet.enums.CellDataTypeEnum;
import org.apache.fesod.sheet.metadata.data.WriteCellData;
import org.apache.fesod.sheet.metadata.property.ExcelContentProperty;
import org.apache.fesod.sheet.write.handler.CellWriteHandler;
import org.apache.fesod.sheet.write.handler.context.CellWriteHandlerContext;

import java.lang.reflect.Field;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Excel 公式注入安全防御写入拦截处理器
 * <p>
 * 在单元格数据转换完成事件（{@link #afterCellDataConverted}）中，自动识别潜在公式注入字符（{@code =}, {@code +}, {@code -}, {@code @}, {@code \t}, {@code \r}），
 * 默认在写出阶段前置单引号 {@code '} 转译为普通静态文本（CWE-1236 防御）。
 * 对标注有 {@link ExcelFormulaAllowed} 且属性为 true 的字段进行白名单豁免，保持实体数据内存真明文。
 * </p>
 *
 * @author Ateng
 * @since 2026-10-20
 */
public class FormulaProtectionCellWriteHandler implements CellWriteHandler {

    /**
     * 字段公式豁免白名单元数据缓存（提升百万级数据写出时的反射性能）
     */
    private static final Map<Field, Boolean> FORMULA_ALLOWED_CACHE = new ConcurrentHashMap<>();

    @Override
    public void afterCellDataConverted(CellWriteHandlerContext context) {
        // 1. 过滤表头单元格与无效上下文
        if (context == null || Boolean.TRUE.equals(context.getHead())) {
            return;
        }

        // 2. 检查全局配置开关是否开启公式防护
        if (!ExcelUtil.getSecurityProperties().isFormulaProtection()) {
            return;
        }

        // 3. 检查字段是否标注了 @ExcelFormulaAllowed 白名单豁免
        ExcelContentProperty contentProperty = context.getExcelContentProperty();
        if (contentProperty != null && contentProperty.getField() != null) {
            Field field = contentProperty.getField();
            boolean isAllowed = FORMULA_ALLOWED_CACHE.computeIfAbsent(field, f -> {
                ExcelFormulaAllowed annotation = f.getAnnotation(ExcelFormulaAllowed.class);
                return annotation != null && annotation.value();
            });
            if (isAllowed) {
                return;
            }
        }

        // 4. 提取写出单元格数据
        WriteCellData<?> cellData = context.getFirstCellData();
        if (cellData == null) {
            return;
        }

        // 5. 对 String 类型且符合公式特征的文本执行前置单引号转译
        if (cellData.getType() == CellDataTypeEnum.STRING) {
            String rawValue = cellData.getStringValue();
            if (rawValue != null && ExcelSecurityUtil.isFormulaLeading(rawValue) && !ExcelSecurityUtil.isAlreadyEscaped(rawValue)) {
                cellData.setStringValue(ExcelSecurityUtil.escapeFormulaLeading(rawValue));
            }
        } else if (cellData.getType() == null && context.getOriginalValue() instanceof String strVal) {
            if (ExcelSecurityUtil.isFormulaLeading(strVal) && !ExcelSecurityUtil.isAlreadyEscaped(strVal)) {
                cellData.setType(CellDataTypeEnum.STRING);
                cellData.setStringValue(ExcelSecurityUtil.escapeFormulaLeading(strVal));
            }
        }
    }
}
