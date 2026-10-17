package io.github.atengk.boot.excel.handler;

import io.github.atengk.boot.excel.util.ExcelWatermarkUtil;
import org.apache.fesod.sheet.write.handler.SheetWriteHandler;
import org.apache.fesod.sheet.write.handler.context.SheetWriteHandlerContext;
import org.apache.poi.openxml4j.opc.PackagePartName;
import org.apache.poi.openxml4j.opc.PackageRelationship;
import org.apache.poi.openxml4j.opc.PackageRelationshipTypes;
import org.apache.poi.openxml4j.opc.PackagingURIHelper;
import org.apache.poi.openxml4j.opc.TargetMode;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.xssf.streaming.SXSSFSheet;
import org.apache.poi.xssf.streaming.SXSSFWorkbook;
import org.apache.poi.xssf.usermodel.XSSFSheet;
import org.openxmlformats.schemas.spreadsheetml.x2006.main.CTWorksheet;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 工作表半透明防拍照暗水印与只读保护拦截器
 * <p>
 * 在工作表创建完毕后（{@code afterSheetCreate}），将包含指定文字的半透明 45° 倾斜防伪切片注入为工作表底层背景图（Background Picture），
 * 屏幕浏览与纸质打印全屏平铺呈现，且 100% 不遮挡单元格点击与公式输入；同时支持配置密码对工作表实施只读防篡改保护。
 * </p>
 *
 * @author Ateng
 * @since 2026-10-17
 */
public class WatermarkSheetWriteHandler implements SheetWriteHandler {

    private static final Logger log = LoggerFactory.getLogger(WatermarkSheetWriteHandler.class);

    private final String watermarkText;
    private final String protectionPassword;

    public WatermarkSheetWriteHandler(String watermarkText) {
        this(watermarkText, null);
    }

    public WatermarkSheetWriteHandler(String watermarkText, String protectionPassword) {
        this.watermarkText = watermarkText;
        this.protectionPassword = protectionPassword;
    }

    @Override
    public void afterSheetCreate(SheetWriteHandlerContext context) {
        if (context == null || context.getWriteSheetHolder() == null) {
            return;
        }

        Sheet sheet = context.getWriteSheetHolder().getSheet();
        Workbook workbook = context.getWriteWorkbookHolder().getWorkbook();

        // 1. 注入全屏防拍照半透明暗水印底图
        if (watermarkText != null && !watermarkText.isBlank()) {
            applyWatermark(workbook, sheet, watermarkText);
        }

        // 2. 注入只读防篡改密码锁定
        if (protectionPassword != null && !protectionPassword.isBlank()) {
            sheet.protectSheet(protectionPassword);
        }
    }

    private void applyWatermark(Workbook workbook, Sheet sheet, String text) {
        try {
            byte[] pngBytes = ExcelWatermarkUtil.createWatermarkImage(text);
            if (pngBytes.length == 0) {
                return;
            }

            XSSFSheet xssfSheet = resolveXSSFSheet(sheet);
            if (xssfSheet == null) {
                return;
            }

            int pictureIdx = workbook.addPicture(pngBytes, Workbook.PICTURE_TYPE_PNG);
            PackagePartName ppn = PackagingURIHelper.createPartName("/xl/media/image" + (pictureIdx + 1) + ".png");
            PackageRelationship pr = xssfSheet.getPackagePart().addRelationship(ppn, TargetMode.INTERNAL, PackageRelationshipTypes.IMAGE_PART);

            CTWorksheet ctWorksheet = xssfSheet.getCTWorksheet();
            if (ctWorksheet.getPicture() != null) {
                ctWorksheet.getPicture().setId(pr.getId());
            } else {
                ctWorksheet.addNewPicture().setId(pr.getId());
            }
        } catch (Exception e) {
            log.warn("设置 Excel 水印背景失败，跳过水印注入: {}", e.getMessage());
        }
    }

    private XSSFSheet resolveXSSFSheet(Sheet sheet) {
        if (sheet instanceof XSSFSheet xssfSheet) {
            return xssfSheet;
        }
        if (sheet instanceof SXSSFSheet sxssfSheet) {
            SXSSFWorkbook sxssfWorkbook = sxssfSheet.getWorkbook();
            if (sxssfWorkbook != null && sxssfWorkbook.getXSSFWorkbook() != null) {
                XSSFSheet resolved = sxssfWorkbook.getXSSFWorkbook().getSheet(sxssfSheet.getSheetName());
                if (resolved != null) {
                    return resolved;
                }
            }
            try {
                java.lang.reflect.Field field = SXSSFSheet.class.getDeclaredField("_sh");
                field.setAccessible(true);
                return (XSSFSheet) field.get(sxssfSheet);
            } catch (Exception ignored) {
                // ignore
            }
        }
        return null;
    }

    public String getWatermarkText() {
        return watermarkText;
    }

    public String getProtectionPassword() {
        return protectionPassword;
    }
}
