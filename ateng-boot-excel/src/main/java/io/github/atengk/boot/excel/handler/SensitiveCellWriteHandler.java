package io.github.atengk.boot.excel.handler;

import io.github.atengk.boot.excel.annotation.ExcelSensitive;
import io.github.atengk.boot.excel.enums.DesensitizeType;
import org.apache.fesod.sheet.enums.CellDataTypeEnum;
import org.apache.fesod.sheet.metadata.data.WriteCellData;
import org.apache.fesod.sheet.metadata.property.ExcelContentProperty;
import org.apache.fesod.sheet.write.handler.CellWriteHandler;
import org.apache.fesod.sheet.write.handler.context.CellWriteHandlerContext;

import java.lang.reflect.Field;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 敏感数据单元格写出拦截处理器
 * <p>
 * 拦截单元格数据转换完成事件（{@link #afterCellDataConverted}），
 * 自动识别字段上的 {@link ExcelSensitive} 注解并在写出阶段动态掩码。
 * 源 Java 实体对象保持 100% 真实明文，防止掩码脏数据污染内存及持久层。
 * </p>
 *
 * @author Ateng
 * @since 2026-10-16
 */
public class SensitiveCellWriteHandler implements CellWriteHandler {

    /**
     * 字段脱敏注解元数据缓存（提升百万级数据写出时的反射性能）
     */
    private static final Map<Field, Optional<ExcelSensitive>> ANNOTATION_CACHE = new ConcurrentHashMap<>();

    @Override
    public void afterCellDataConverted(CellWriteHandlerContext context) {
        // 1. 过滤表头单元格，仅对数据单元格执行脱敏
        if (Boolean.TRUE.equals(context.getHead())) {
            return;
        }

        // 2. 提取当前单元格对应的 Java 字段与脱敏注解（带缓存）
        ExcelContentProperty contentProperty = context.getExcelContentProperty();
        if (contentProperty == null || contentProperty.getField() == null) {
            return;
        }

        Field field = contentProperty.getField();
        ExcelSensitive sensitive = ANNOTATION_CACHE
                .computeIfAbsent(field, f -> Optional.ofNullable(f.getAnnotation(ExcelSensitive.class)))
                .orElse(null);
        if (sensitive == null) {
            return;
        }

        // 3. 提取单元格文本数据
        WriteCellData<?> cellData = context.getFirstCellData();
        if (cellData == null) {
            return;
        }

        String rawValue = cellData.getStringValue();
        if (rawValue == null && context.getOriginalValue() != null) {
            rawValue = String.valueOf(context.getOriginalValue());
        }
        if (rawValue == null || rawValue.isEmpty()) {
            return;
        }

        // 4. 执行动态脱敏掩码转换，并更新输出单元格数据
        String maskedValue = DesensitizeType.mask(rawValue, sensitive);

        cellData.setType(CellDataTypeEnum.STRING);
        cellData.setStringValue(maskedValue);
    }
}
