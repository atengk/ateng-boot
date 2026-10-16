package io.github.atengk.boot.excel.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.apache.fesod.sheet.annotation.ExcelProperty;
import org.apache.fesod.sheet.annotation.format.DateTimeFormat;
import org.apache.fesod.sheet.annotation.write.style.ColumnWidth;

import java.io.Serializable;
import java.math.BigDecimal;
import java.util.Date;

/**
 * 单元测试模型：测试用户实体
 *
 * @author Ateng
 * @since 2026-10-16
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class DemoUser implements Serializable {

    private static final long serialVersionUID = 1L;

    @ExcelProperty(value = "用户ID", index = 0)
    @ColumnWidth(15)
    private Long id;

    @ExcelProperty(value = "用户名", index = 1)
    @ColumnWidth(20)
    private String username;

    @ExcelProperty(value = "年龄", index = 2)
    @ColumnWidth(10)
    private Integer age;

    @ExcelProperty(value = "账户余额", index = 3)
    @ColumnWidth(15)
    private BigDecimal balance;

    @ExcelProperty(value = "创建时间", index = 4)
    @DateTimeFormat("yyyy-MM-dd HH:mm:ss")
    @ColumnWidth(22)
    private Date createTime;
}
