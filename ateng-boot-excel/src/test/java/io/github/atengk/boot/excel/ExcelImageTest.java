package io.github.atengk.boot.excel;

import io.github.atengk.boot.excel.annotation.ExcelImage;
import io.github.atengk.boot.excel.util.ExcelUtil;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.apache.fesod.sheet.annotation.ExcelProperty;
import org.apache.poi.ss.usermodel.PictureData;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.ss.usermodel.WorkbookFactory;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import javax.imageio.ImageIO;
import java.awt.*;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 多媒体图片与行高自适应联动集成测试
 *
 * @author Ateng
 * @since 2026-10-17
 */
class ExcelImageTest {

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    public static class ProductVO {
        @ExcelProperty("商品名称")
        private String name;

        @ExcelProperty("商品封面")
        @ExcelImage(rowHeight = 70)
        private String coverImage;

        @ExcelProperty("商品备注")
        private String remark;
    }

    private void saveTempExcel(String fileName, byte[] bytes) throws IOException {
        Path tempDir = Paths.get(System.getProperty("java.io.tmpdir"), "ateng-excel-test");
        Files.createDirectories(tempDir);
        File tempFile = tempDir.resolve(fileName).toFile();
        try (FileOutputStream fos = new FileOutputStream(tempFile)) {
            fos.write(bytes);
        }
        System.out.println("====== [ExcelImageTest] Excel 文件已落盘 ======");
        System.out.println("文件绝对路径: " + tempFile.getAbsolutePath());
        System.out.println("本地直达链接: file:///" + tempFile.getAbsolutePath().replace("\\", "/"));
        System.out.println("===============================================");
    }

    private byte[] createTestPng(Color color) throws IOException {
        BufferedImage img = new BufferedImage(40, 40, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = img.createGraphics();
        g.setColor(color);
        g.fillRect(0, 0, 40, 40);
        g.dispose();
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        ImageIO.write(img, "png", baos);
        return baos.toByteArray();
    }

    @Test
    @DisplayName("测试 @ExcelImage 行高自适应撑高、Base64 与本地文件图片写入")
    void testExcelImageExport() throws Exception {
        // 1. 生成测试图片：Base64 字符串与本地文件路径
        byte[] bluePng = createTestPng(Color.BLUE);
        String base64Image = "data:image/png;base64," + Base64.getEncoder().encodeToString(bluePng);

        Path tempImgPath = Paths.get(System.getProperty("java.io.tmpdir"), "ateng-excel-test", "test-product-cover.png");
        Files.createDirectories(tempImgPath.getParent());
        byte[] greenPng = createTestPng(Color.GREEN);
        Files.write(tempImgPath, greenPng);

        List<ProductVO> list = new ArrayList<>();
        list.add(new ProductVO("智能手表 Pro (Base64)", base64Image, "支持心率健康监测"));
        list.add(new ProductVO("运动手环 Mini (本地路径)", tempImgPath.toAbsolutePath().toString(), "轻量持久续航"));
        list.add(new ProductVO("纯文本商品 (无图片)", null, "无图片封面直通"));

        // 2. 流式导出
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ExcelUtil.export(out, list, ProductVO.class);
        byte[] excelBytes = out.toByteArray();
        assertNotNull(excelBytes);
        assertTrue(excelBytes.length > 0);

        saveTempExcel("test-image-export.xlsx", excelBytes);

        // 3. 验证 POI 工作表与行高
        try (Workbook workbook = WorkbookFactory.create(new ByteArrayInputStream(excelBytes))) {
            Sheet sheet = workbook.getSheetAt(0);
            assertNotNull(sheet);

            // 验证数据行行高撑高至 @ExcelImage(rowHeight = 70)
            Row row1 = sheet.getRow(1);
            assertNotNull(row1);
            assertEquals(70.0f, row1.getHeightInPoints(), 0.5f, "行高应自动适配为 70pt");

            Row row2 = sheet.getRow(2);
            assertNotNull(row2);
            assertEquals(70.0f, row2.getHeightInPoints(), 0.5f, "行高应自动适配为 70pt");

            // 验证图片资源已嵌入工作簿
            List<? extends PictureData> pictures = workbook.getAllPictures();
            assertFalse(pictures.isEmpty(), "工作簿中应包含嵌入的图片数据");
            assertTrue(pictures.size() >= 2, "应至少包含 Base64 和本地文件转换的两张图片");
        }
    }
}
