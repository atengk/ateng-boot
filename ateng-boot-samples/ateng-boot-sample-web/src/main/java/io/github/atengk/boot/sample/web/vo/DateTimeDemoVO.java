package io.github.atengk.boot.sample.web.vo;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;

/**
 * 时间日期与雪花 ID 序列化演示视图对象
 * 演示 JacksonCustomizer 针对 Long 精度与标准时间格式的统一转换
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
