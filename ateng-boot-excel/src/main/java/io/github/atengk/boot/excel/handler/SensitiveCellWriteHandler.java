package io.github.atengk.boot.excel.handler;

import io.github.atengk.boot.core.enums.SensitiveType;
import io.github.atengk.boot.excel.annotation.ExcelSensitive;
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
     * 字段脱敏规则元数据缓存（提升百万级数据写出时的反射性能）
     */
    private static final Map<Field, Optional<SensitiveRule>> RULE_CACHE = new ConcurrentHashMap<>();

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
        SensitiveRule rule = RULE_CACHE
                .computeIfAbsent(field, SensitiveCellWriteHandler::resolveRule)
                .orElse(null);
        if (rule == null) {
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
        String maskedValue = rule.type().mask(
                rawValue,
                rule.startInclude(),
                rule.endExclude(),
                rule.keepHead(),
                rule.keepTail(),
                rule.maskChar()
        );

        cellData.setType(CellDataTypeEnum.STRING);
        cellData.setStringValue(maskedValue);
    }

    private static Optional<SensitiveRule> resolveRule(Field field) {
        // 1. 优先提取 ExcelSensitive 注解
        ExcelSensitive excelSensitive = field.getAnnotation(ExcelSensitive.class);
        if (excelSensitive != null) {
            SensitiveType targetType = excelSensitive.type();
            if (targetType == SensitiveType.CUSTOM && excelSensitive.value() != SensitiveType.CUSTOM) {
                targetType = excelSensitive.value();
            }
            if (targetType == SensitiveType.CUSTOM && (excelSensitive.keepHead() > 0 || excelSensitive.keepTail() > 0)) {
                targetType = SensitiveType.KEEP_HEAD_TAIL;
            }
            return Optional.of(new SensitiveRule(
                    targetType,
                    excelSensitive.startInclude(),
                    excelSensitive.endExclude(),
                    excelSensitive.keepHead(),
                    excelSensitive.keepTail(),
                    excelSensitive.maskChar()
            ));
        }

        // 2. 回退提取 Core 模块通用的 FieldSensitive 注解
        io.github.atengk.boot.core.annotation.FieldSensitive fieldSensitive =
                field.getAnnotation(io.github.atengk.boot.core.annotation.FieldSensitive.class);
        if (fieldSensitive != null) {
            SensitiveType targetType = fieldSensitive.value();
            if (targetType == SensitiveType.CUSTOM && (fieldSensitive.keepHead() > 0 || fieldSensitive.keepTail() > 0)) {
                targetType = SensitiveType.KEEP_HEAD_TAIL;
            }
            return Optional.of(new SensitiveRule(
                    targetType,
                    fieldSensitive.startInclude(),
                    fieldSensitive.endExclude(),
                    fieldSensitive.keepHead(),
                    fieldSensitive.keepTail(),
                    fieldSensitive.maskChar()
            ));
        }

        return Optional.empty();
    }

    /**
     * 脱敏规则轻量元数据记录
     */
    private record SensitiveRule(
            SensitiveType type,
            int startInclude,
            int endExclude,
            int keepHead,
            int keepTail,
            char maskChar
    ) {}
}
