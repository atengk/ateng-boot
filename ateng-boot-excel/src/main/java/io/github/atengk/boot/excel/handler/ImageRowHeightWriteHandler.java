package io.github.atengk.boot.excel.handler;

import io.github.atengk.boot.excel.annotation.ExcelImage;
import org.apache.fesod.sheet.write.handler.RowWriteHandler;
import org.apache.fesod.sheet.write.handler.context.RowWriteHandlerContext;

import java.lang.reflect.Field;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 多媒体图片行高自适应补偿拦截器
 * <p>
 * 配合 {@link ExcelImage} 运行，在数据行写入完成时自动检测实体类是否存在图片字段，
 * 并将数据行高自适应撑开至目标尺寸（默认 60pt），彻底根治原生图片挤压变形与遮挡缺陷。
 * </p>
 *
 * @author Ateng
 * @since 2026-10-17
 */
public class ImageRowHeightWriteHandler implements RowWriteHandler {

    private static final Map<Class<?>, Short> ROW_HEIGHT_CACHE = new ConcurrentHashMap<>();

    private final short rowHeight;

    public ImageRowHeightWriteHandler(Class<?> targetClass) {
        this.rowHeight = (targetClass != null) ? resolveMaxRowHeight(targetClass) : 0;
    }

    public ImageRowHeightWriteHandler(short rowHeight) {
        this.rowHeight = rowHeight;
    }

    @Override
    public void afterRowDispose(RowWriteHandlerContext context) {
        if (context == null || context.getRow() == null || Boolean.TRUE.equals(context.getHead())) {
            return;
        }

        if (rowHeight > 0) {
            context.getRow().setHeightInPoints(rowHeight);
        }
    }

    /**
     * 判断指定实体类是否标注了 {@link ExcelImage}
     */
    public static boolean hasExcelImage(Class<?> clazz) {
        return clazz != null && resolveMaxRowHeight(clazz) > 0;
    }

    private static short resolveMaxRowHeight(Class<?> clazz) {
        return ROW_HEIGHT_CACHE.computeIfAbsent(clazz, c -> {
            short max = 0;
            for (Class<?> current = c; current != null && current != Object.class; current = current.getSuperclass()) {
                for (Field field : current.getDeclaredFields()) {
                    ExcelImage annotation = field.getAnnotation(ExcelImage.class);
                    if (annotation != null) {
                        max = (short) Math.max(max, annotation.rowHeight());
                    }
                }
            }
            return max;
        });
    }

    public short getRowHeight() {
        return rowHeight;
    }
}
