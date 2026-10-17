package io.github.atengk.boot.core.constant;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;

import static org.junit.jupiter.api.Assertions.*;

/**
 * DatePattern 常量标准件单元测试
 *
 * @author Ateng
 * @since 2026-10-17
 */
@DisplayName("DatePattern 常量标准件单元测试")
class DatePatternTest {

    @Test
    @DisplayName("测试标准日期时间格式化与解析")
    void testNormDateTime() {
        LocalDateTime now = LocalDateTime.of(2026, 10, 17, 9, 30, 45);
        String formatted = DatePattern.NORM_DATETIME_FORMATTER.format(now);
        assertEquals("2026-10-17 09:30:45", formatted);

        LocalDateTime parsed = LocalDateTime.parse(formatted, DatePattern.NORM_DATETIME_FORMATTER);
        assertEquals(now, parsed);
        assertEquals("yyyy-MM-dd HH:mm:ss", DatePattern.NORM_DATETIME_PATTERN);
    }

    @Test
    @DisplayName("测试标准日期格式化与解析")
    void testNormDate() {
        LocalDate date = LocalDate.of(2026, 10, 17);
        String formatted = DatePattern.NORM_DATE_FORMATTER.format(date);
        assertEquals("2026-10-17", formatted);

        LocalDate parsed = LocalDate.parse(formatted, DatePattern.NORM_DATE_FORMATTER);
        assertEquals(date, parsed);
        assertEquals("yyyy-MM-dd", DatePattern.NORM_DATE_PATTERN);
    }

    @Test
    @DisplayName("测试标准时间格式化与解析")
    void testNormTime() {
        LocalTime time = LocalTime.of(9, 30, 45);
        String formatted = DatePattern.NORM_TIME_FORMATTER.format(time);
        assertEquals("09:30:45", formatted);

        LocalTime parsed = LocalTime.parse(formatted, DatePattern.NORM_TIME_FORMATTER);
        assertEquals(time, parsed);
        assertEquals("HH:mm:ss", DatePattern.NORM_TIME_PATTERN);
    }

    @Test
    @DisplayName("测试紧凑纯数字格式化与解析")
    void testPureDateTime() {
        LocalDateTime now = LocalDateTime.of(2026, 10, 17, 9, 30, 45);
        assertEquals("20261017093045", DatePattern.PURE_DATETIME_FORMATTER.format(now));
        assertEquals("20261017", DatePattern.PURE_DATE_FORMATTER.format(now.toLocalDate()));
        assertEquals("093045", DatePattern.PURE_TIME_FORMATTER.format(now.toLocalTime()));
    }

    @Test
    @DisplayName("测试带毫秒日期时间格式化")
    void testDateTimeWithMs() {
        LocalDateTime now = LocalDateTime.of(2026, 10, 17, 9, 30, 45, 123000000);
        String formatted = DatePattern.NORM_DATETIME_MS_FORMATTER.format(now);
        assertEquals("2026-10-17 09:30:45.123", formatted);
    }

    @Test
    @DisplayName("测试默认时区常量")
    void testDefaultTimeZone() {
        assertEquals("Asia/Shanghai", DatePattern.DEFAULT_TIME_ZONE);
    }
}
