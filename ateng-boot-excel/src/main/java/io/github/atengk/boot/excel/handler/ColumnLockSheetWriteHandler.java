package io.github.atengk.boot.excel.handler;

import io.github.atengk.boot.excel.annotation.ExcelColumnLock;
import org.apache.fesod.sheet.annotation.ExcelIgnore;
import org.apache.fesod.sheet.annotation.ExcelProperty;
import org.apache.fesod.sheet.write.handler.CellWriteHandler;
import org.apache.fesod.sheet.write.handler.SheetWriteHandler;
import org.apache.fesod.sheet.write.handler.context.CellWriteHandlerContext;
import org.apache.fesod.sheet.write.handler.context.SheetWriteHandlerContext;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.CellStyle;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Excel 列级防呆只读锁定与工作表保护写入拦截处理器
 * <p>
 * 拦截工作表创建（{@link #afterSheetCreate}）与单元格写出（{@link #afterCellDispose}）事件，
 * 根据实体类或字段上的 {@link ExcelColumnLock} 注解或编程式配置：
 * <ol>
 *     <li>对锁定列单元格显式设置锁定样式（{@code CellStyle.setLocked(true)}）；</li>
 *     <li>对允许编辑的非锁定列单元格显式解除锁定（{@code CellStyle.setLocked(false)}），并设置列默认解锁样式；</li>
 *     <li>自动激活工作表保护（{@code sheet.protectSheet(password)}），实现模板防呆输入与数据防篡改保护。</li>
 * </ol>
 * 内部基于工作簿级别的 {@link CellStyle} 映射缓存，严防突破 POI 64,000 样式上限。
 * </p>
 *
 * @author Ateng
 * @since 2026-10-20
 */
public class ColumnLockSheetWriteHandler implements SheetWriteHandler, CellWriteHandler {

    private static final Map<Class<?>, Optional<ColumnLockMeta>> CLASS_META_CACHE = new ConcurrentHashMap<>();

    private final Class<?> targetClass;
    private final String overridePassword;
    private final Set<Integer> explicitLockedColumns;
    private final Set<Integer> explicitUnlockedColumns;

    /**
     * 工作簿级样式缓存 (Workbook -> (OriginalStyle -> ModifiedStyle))
     */
    private final Map<Workbook, Map<CellStyle, CellStyle>> unlockedStyleCache = new ConcurrentHashMap<>();
    private final Map<Workbook, Map<CellStyle, CellStyle>> lockedStyleCache = new ConcurrentHashMap<>();

    public ColumnLockSheetWriteHandler(Class<?> targetClass) {
        this(targetClass, null);
    }

    public ColumnLockSheetWriteHandler(Class<?> targetClass, String password) {
        this.targetClass = targetClass;
        this.overridePassword = password;
        this.explicitLockedColumns = null;
        this.explicitUnlockedColumns = null;
    }

    public ColumnLockSheetWriteHandler(Collection<Integer> lockedColumns, String password) {
        this(lockedColumns, Collections.emptySet(), password);
    }

    public ColumnLockSheetWriteHandler(Collection<Integer> lockedColumns, Collection<Integer> unlockedColumns, String password) {
        this.targetClass = null;
        this.overridePassword = password;
        this.explicitLockedColumns = (lockedColumns != null) ? new HashSet<>(lockedColumns) : Collections.emptySet();
        this.explicitUnlockedColumns = (unlockedColumns != null) ? new HashSet<>(unlockedColumns) : Collections.emptySet();
    }

    /**
     * 判断目标实体类是否声明了列级只读锁定注解
     *
     * @param clazz 待检查实体 Class
     * @return true 表示类或字段标注了有效的 @ExcelColumnLock
     */
    public static boolean hasColumnLock(Class<?> clazz) {
        if (clazz == null) {
            return false;
        }
        return resolveMeta(clazz).map(ColumnLockMeta::hasAnyLock).orElse(false);
    }

    @Override
    public int order() {
        // 保证在 Fesod 默认的 FillStyleCellWriteHandler (order = 50000) 之后执行，防止样式被覆写
        return 70000;
    }

    @Override
    public void afterSheetCreate(SheetWriteHandlerContext context) {
        if (context == null || context.getWriteSheetHolder() == null) {
            return;
        }

        Sheet sheet = context.getWriteSheetHolder().getSheet();
        Workbook workbook = context.getWriteWorkbookHolder().getWorkbook();
        if (sheet == null || workbook == null) {
            return;
        }

        ColumnLockMeta meta = resolveCurrentMeta();
        if (meta == null || !meta.hasAnyLock()) {
            return;
        }

        // 1. 开启工作表保护
        String pwd = (overridePassword != null) ? overridePassword : meta.password();
        sheet.protectSheet(pwd != null ? pwd : "");

        // 2. 为非锁定列注册默认列样式（解除锁定），便于用户在模板后续行输入
        CellStyle defaultUnlockedStyle = workbook.createCellStyle();
        defaultUnlockedStyle.setLocked(false);
        for (Integer unlockedCol : meta.unlockedColumns()) {
            sheet.setDefaultColumnStyle(unlockedCol, defaultUnlockedStyle);
        }
    }

    @Override
    public void afterCellDispose(CellWriteHandlerContext context) {
        if (context == null || context.getCell() == null || Boolean.TRUE.equals(context.getHead())) {
            // 表头单元格保持 Excel 默认锁定，仅处理正文数据单元格
            return;
        }

        ColumnLockMeta meta = resolveCurrentMeta();
        if (meta == null || !meta.hasAnyLock()) {
            return;
        }

        Cell cell = context.getCell();
        Workbook workbook = cell.getSheet().getWorkbook();
        int columnIndex = context.getColumnIndex();

        boolean shouldLock = meta.isLocked(columnIndex);
        CellStyle originalStyle = cell.getCellStyle();

        if (shouldLock) {
            cell.setCellStyle(getOrCreateLockedStyle(workbook, originalStyle));
        } else {
            cell.setCellStyle(getOrCreateUnlockedStyle(workbook, originalStyle));
        }
    }

    private CellStyle getOrCreateUnlockedStyle(Workbook workbook, CellStyle originalStyle) {
        Map<CellStyle, CellStyle> map = unlockedStyleCache.computeIfAbsent(workbook, wb -> new ConcurrentHashMap<>());
        return map.computeIfAbsent(originalStyle, orig -> {
            CellStyle clone = workbook.createCellStyle();
            clone.cloneStyleFrom(orig);
            clone.setLocked(false);
            return clone;
        });
    }

    private CellStyle getOrCreateLockedStyle(Workbook workbook, CellStyle originalStyle) {
        Map<CellStyle, CellStyle> map = lockedStyleCache.computeIfAbsent(workbook, wb -> new ConcurrentHashMap<>());
        return map.computeIfAbsent(originalStyle, orig -> {
            CellStyle clone = workbook.createCellStyle();
            clone.cloneStyleFrom(orig);
            clone.setLocked(true);
            return clone;
        });
    }

    private ColumnLockMeta resolveCurrentMeta() {
        if (explicitLockedColumns != null) {
            return new ColumnLockMeta(overridePassword != null ? overridePassword : "", explicitLockedColumns, explicitUnlockedColumns, !explicitLockedColumns.isEmpty());
        }
        if (targetClass != null) {
            return resolveMeta(targetClass).orElse(null);
        }
        return null;
    }

    private static Optional<ColumnLockMeta> resolveMeta(Class<?> clazz) {
        return CLASS_META_CACHE.computeIfAbsent(clazz, ColumnLockSheetWriteHandler::doResolveMeta);
    }

    private static Optional<ColumnLockMeta> doResolveMeta(Class<?> clazz) {
        if (clazz == null) {
            return Optional.empty();
        }

        ExcelColumnLock classLock = clazz.getAnnotation(ExcelColumnLock.class);
        String password = (classLock != null) ? classLock.password() : "";
        boolean classDefaultLocked = (classLock != null && classLock.locked());

        List<Field> allFields = new ArrayList<>();
        Class<?> current = clazz;
        List<Class<?>> hierarchy = new ArrayList<>();
        while (current != null && current != Object.class) {
            hierarchy.add(0, current);
            current = current.getSuperclass();
        }
        for (Class<?> clz : hierarchy) {
            for (Field field : clz.getDeclaredFields()) {
                if (!Modifier.isStatic(field.getModifiers()) && !Modifier.isTransient(field.getModifiers())) {
                    allFields.add(field);
                }
            }
        }

        boolean hasFieldAnnotations = allFields.stream().anyMatch(f -> f.isAnnotationPresent(ExcelColumnLock.class));
        Set<Integer> lockedColumns = new HashSet<>();
        Set<Integer> unlockedColumns = new HashSet<>();
        int nextIndex = 0;

        for (Field field : allFields) {
            if (field.isAnnotationPresent(ExcelIgnore.class)) {
                continue;
            }

            ExcelProperty excelProperty = field.getAnnotation(ExcelProperty.class);
            int columnIndex = (excelProperty != null && excelProperty.index() >= 0) ? excelProperty.index() : nextIndex;
            nextIndex = Math.max(nextIndex, columnIndex + 1);

            ExcelColumnLock fieldLock = field.getAnnotation(ExcelColumnLock.class);
            if (fieldLock != null) {
                if (password.isEmpty() && !fieldLock.password().isEmpty()) {
                    password = fieldLock.password();
                }
                if (fieldLock.locked()) {
                    lockedColumns.add(columnIndex);
                } else {
                    unlockedColumns.add(columnIndex);
                }
            } else if (!hasFieldAnnotations && classDefaultLocked) {
                lockedColumns.add(columnIndex);
            } else {
                unlockedColumns.add(columnIndex);
            }
        }

        boolean hasAnyLock = classDefaultLocked || hasFieldAnnotations || !lockedColumns.isEmpty();
        if (!hasAnyLock) {
            return Optional.empty();
        }

        return Optional.of(new ColumnLockMeta(password, lockedColumns, unlockedColumns, true));
    }

    /**
     * 列级只读锁定元数据记录
     */
    public record ColumnLockMeta(
            String password,
            Set<Integer> lockedColumns,
            Set<Integer> unlockedColumns,
            boolean hasAnyLock
    ) {
        public boolean isLocked(int columnIndex) {
            if (lockedColumns.contains(columnIndex)) {
                return true;
            }
            if (unlockedColumns.contains(columnIndex)) {
                return false;
            }
            return false;
        }
    }
}
