package io.github.atengk.boot.sample.web.vo;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;

/**
 * 时间日期、高精度金额与雪花 ID 序列化演示视图对象
 * 演示 JacksonCustomizer 针对 Long 精度、BigDecimal 防科学计数法与标准时间格式的统一转换
 *
 * @author Ateng
 * @since 2026-10-19
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class DateTimeDemoVO {

    /**
     * 超大 64 位雪花 ID（如 1846182948192841920L，序列化为 String）
     */
    private Long id;

    /**
     * 高精度订单金额（如 new BigDecimal("1E+7")，开启 WRITE_BIGDECIMAL_AS_PLAIN 后平铺输出为 10000000）
     */
    private BigDecimal amount;

    /**
     * 标准日期时间（yyyy-MM-dd HH:mm:ss）
     */
    private LocalDateTime dateTime;

    /**
     * 标准日期（yyyy-MM-dd）
     */
    private LocalDate date;

    /**
     * 标准时间（HH:mm:ss）
     */
    private LocalTime time;
}
