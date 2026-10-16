package io.github.atengk.boot.excel.handler;

import io.github.atengk.boot.excel.annotation.ExcelMerge;
import org.apache.fesod.sheet.metadata.data.WriteCellData;
import org.apache.fesod.sheet.metadata.property.ExcelContentProperty;
import org.apache.fesod.sheet.write.handler.CellWriteHandler;
import org.apache.fesod.sheet.write.handler.SheetWriteHandler;
import org.apache.fesod.sheet.write.handler.context.CellWriteHandlerContext;
import org.apache.fesod.sheet.write.handler.context.SheetWriteHandlerContext;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.util.CellRangeAddress;

import java.lang.reflect.Field;
import java.util.Collections;
import java.util.HashSet;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Excel 相邻行单元格纵向动态合并处理器
 * <p>
 * 基于 {@link CellWriteHandler} 和 {@link SheetWriteHandler} 拦截写出事件，
 * 对声明了 {@link ExcelMerge} 或指定列索引的相邻相同值单元格执行纵向合并，消除手工合并代码。
 * </p>
 *
 * @author Ateng
 * @since 2026-10-16
 */
public class MergeCellWriteHandler implements CellWriteHandler, SheetWriteHandler {

    private static final Map<Class<?>, Boolean> MERGE_CLASS_CACHE = new ConcurrentHashMap<>();
    private static final Map<Field, ExcelMerge> MERGE_FIELD_CACHE = new ConcurrentHashMap<>();

    private final Set<Integer> explicitMergeColumns;
    private final Map<Sheet, Map<Integer, ColumnTracker>> sheetTrackers = new ConcurrentHashMap<>();

    /**
     * 默认构造函数：根据实体类字段上的 {@link ExcelMerge} 注解自动识别合并列
     */
    public MergeCellWriteHandler() {
        this.explicitMergeColumns = Collections.emptySet();
    }

    /**
     * 显式指定列索引构造函数：对指定列索引强制启用纵向合并（常用于无实体动态导出）
     *
     * @param columnIndexes 需要纵向合并的 0-based 列索引数组
     */
    public MergeCellWriteHandler(int... columnIndexes) {
        if (columnIndexes != null && columnIndexes.length > 0) {
            Set<Integer> set = new HashSet<>(columnIndexes.length * 2);
            for (int col : columnIndexes) {
                set.add(col);
            }
            this.explicitMergeColumns = Collections.unmodifiableSet(set);
        } else {
            this.explicitMergeColumns = Collections.emptySet();
        }
    }

    /**
     * 判断目标实体类继承树中是否存在标记了 {@link ExcelMerge} 的有效字段（带高并发缓存保护）
     *
     * @param clazz 待检查的实体类
     * @return 若存在合并字段返回 {@code true}，否则返回 {@code false}
     */
    public static boolean hasMergeAnnotation(Class<?> clazz) {
        if (clazz == null || clazz == Object.class) {
            return false;
        }
        return MERGE_CLASS_CACHE.computeIfAbsent(clazz, c -> {
            Class<?> current = c;
            while (current != null && current != Object.class) {
                for (Field field : current.getDeclaredFields()) {
                    ExcelMerge merge = field.getAnnotation(ExcelMerge.class);
                    if (merge != null && merge.value()) {
                        return true;
                    }
                }
                current = current.getSuperclass();
            }
            return false;
        });
    }

    @Override
    public void afterCellDispose(CellWriteHandlerContext context) {
        // 1. 忽略表头行与空上下文
        if (Boolean.TRUE.equals(context.getHead())) {
            return;
        }
        if (context.getWriteSheetHolder() == null || context.getWriteSheetHolder().getSheet() == null) {
            return;
        }

        int colIndex = context.getColumnIndex() != null ? context.getColumnIndex() : 0;
        ExcelMerge excelMerge = resolveMergeAnnotation(context);
        boolean isMergeCol = explicitMergeColumns.contains(colIndex)
                || (excelMerge != null && excelMerge.value());

        if (!isMergeCol) {
            return;
        }

        boolean dependParent = (excelMerge != null && excelMerge.dependParent());
        Sheet sheet = context.getWriteSheetHolder().getSheet();
        int currentRow = context.getRowIndex() != null ? context.getRowIndex() : 0;
        Object currentVal = extractCellValue(context);

        Map<Integer, ColumnTracker> trackers = sheetTrackers.computeIfAbsent(sheet, k -> new ConcurrentHashMap<>());
        ColumnTracker tracker = trackers.computeIfAbsent(colIndex, k -> new ColumnTracker(colIndex, dependParent));

        // 2. 检查最近的前序合并主列是否在当前行发生了换组/断裂（防御非相邻列场景）
        boolean parentBroke = false;
        if (dependParent && colIndex > 0) {
            ColumnTracker parentTracker = null;
            for (int i = colIndex - 1; i >= 0; i--) {
                if (trackers.containsKey(i)) {
                    parentTracker = trackers.get(i);
                    break;
                }
            }
            if (parentTracker != null && parentTracker.startRowIndex == currentRow) {
                parentBroke = true;
            }
        }

        if (tracker.startRowIndex == -1) {
            // 首行单元格初始化
            tracker.startRowIndex = currentRow;
            tracker.lastRowIndex = currentRow;
            tracker.lastValue = currentVal;
            return;
        }

        // 3. 判断是否满足合并延续条件
        boolean sameValue = !parentBroke && isSameValue(tracker.lastValue, currentVal);

        if (sameValue) {
            tracker.lastRowIndex = currentRow;
        } else {
            // 4. 值发生变更或父级断裂，冲刷闭合前面已积累的区间并开启新区间
            flushMerge(sheet, tracker);
            tracker.startRowIndex = currentRow;
            tracker.lastRowIndex = currentRow;
            tracker.lastValue = currentVal;
        }
    }

    @Override
    public void afterSheetDispose(SheetWriteHandlerContext context) {
        if (context.getWriteSheetHolder() == null || context.getWriteSheetHolder().getSheet() == null) {
            return;
        }
        Sheet sheet = context.getWriteSheetHolder().getSheet();
        Map<Integer, ColumnTracker> trackers = sheetTrackers.remove(sheet);
        if (trackers != null) {
            for (ColumnTracker tracker : trackers.values()) {
                flushMerge(sheet, tracker);
            }
        }
    }

    private void flushMerge(Sheet sheet, ColumnTracker tracker) {
        if (tracker.lastRowIndex > tracker.startRowIndex) {
            sheet.addMergedRegionUnsafe(new CellRangeAddress(
                    tracker.startRowIndex,
                    tracker.lastRowIndex,
                    tracker.columnIndex,
                    tracker.columnIndex
            ));
        }
    }

    private boolean isSameValue(Object lastVal, Object currentVal) {
        if (lastVal == null || currentVal == null) {
            return false;
        }
        if (lastVal instanceof String s && s.isEmpty()) {
            return false;
        }
        if (currentVal instanceof String s && s.isEmpty()) {
            return false;
        }
        return Objects.equals(lastVal, currentVal);
    }

    private Object extractCellValue(CellWriteHandlerContext context) {
        WriteCellData<?> cellData = context.getFirstCellData();
        if (cellData != null) {
            if (cellData.getStringValue() != null) {
                return cellData.getStringValue();
            }
            if (cellData.getNumberValue() != null) {
                return cellData.getNumberValue();
            }
            if (cellData.getBooleanValue() != null) {
                return cellData.getBooleanValue();
            }
            if (cellData.getData() != null) {
                return cellData.getData();
            }
        }
        return context.getOriginalValue();
    }

    private ExcelMerge resolveMergeAnnotation(CellWriteHandlerContext context) {
        ExcelContentProperty contentProperty = context.getExcelContentProperty();
        if (contentProperty == null || contentProperty.getField() == null) {
            return null;
        }
        Field field = contentProperty.getField();
        return MERGE_FIELD_CACHE.computeIfAbsent(field, f -> f.getAnnotation(ExcelMerge.class));
    }

    /**
     * 单列纵向合并状态追踪器
     */
    private static class ColumnTracker {
        final int columnIndex;
        final boolean dependParent;
        int startRowIndex = -1;
        int lastRowIndex = -1;
        Object lastValue = null;

        ColumnTracker(int columnIndex, boolean dependParent) {
            this.columnIndex = columnIndex;
            this.dependParent = dependParent;
        }
    }
}
