package io.github.atengk.boot.core.util;

import io.github.atengk.boot.core.constant.DatePattern;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.Period;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.Date;

import static org.junit.jupiter.api.Assertions.*;

/**
 * DateTimeUtil 单元测试
 *
 * @author Ateng
 * @since 2026-10-18
 */
class DateTimeUtilTest {

    @Test
    @DisplayName("验证默认时区获取与时区透传")
    void shouldObtainDefaultZoneId() {
        assertNotNull(DateTimeUtil.getDefaultZoneId());
        assertEquals(ZoneId.systemDefault(), DateTimeUtil.getDefaultZoneId());
    }

    @Test
    @DisplayName("验证 Date 与 LocalDateTime 互转无损且毫秒对齐")
    void shouldConvertBetweenDateAndLocalDateTimeLosslessly() {
        LocalDateTime now = LocalDateTime.of(2026, 10, 18, 15, 30, 45, 123_000_000);
        Date date = DateTimeUtil.toDate(now);
        assertNotNull(date);

        LocalDateTime convertedBack = DateTimeUtil.toLocalDateTime(date);
        assertNotNull(convertedBack);
        assertEquals(now.getYear(), convertedBack.getYear());
        assertEquals(now.getMonth(), convertedBack.getMonth());
        assertEquals(now.getDayOfMonth(), convertedBack.getDayOfMonth());
        assertEquals(now.getHour(), convertedBack.getHour());
        assertEquals(now.getMinute(), convertedBack.getMinute());
        assertEquals(now.getSecond(), convertedBack.getSecond());
        assertEquals(123_000_000, convertedBack.getNano());

        // 指定自定义时区
        ZoneId tokyoZone = ZoneId.of("Asia/Tokyo");
        Date tokyoDate = DateTimeUtil.toDate(now, tokyoZone);
        LocalDateTime fromTokyo = DateTimeUtil.toLocalDateTime(tokyoDate, tokyoZone);
        assertEquals(now, fromTokyo);
    }

    @Test
    @DisplayName("验证时间戳与 LocalDateTime/LocalDate 互转")
    void shouldConvertBetweenEpochMilliAndTemporal() {
        long epochMilli = 1792315845123L; // 某一固定毫秒时间戳
        LocalDateTime ldt = DateTimeUtil.toLocalDateTime(epochMilli);
        assertNotNull(ldt);

        Long convertedEpoch = DateTimeUtil.toEpochMilli(ldt);
        assertEquals(epochMilli, convertedEpoch);

        // LocalDate 与 时间戳互转（当天零点）
        LocalDate localDate = LocalDate.of(2026, 10, 18);
        Long dateEpoch = DateTimeUtil.toEpochMilli(localDate);
        assertNotNull(dateEpoch);

        LocalDate convertedLocalDate = DateTimeUtil.toLocalDate(dateEpoch);
        assertEquals(localDate, convertedLocalDate);

        // Instant 互转
        Instant instant = DateTimeUtil.toInstant(ldt);
        assertNotNull(instant);
        assertEquals(epochMilli, instant.toEpochMilli());
        assertEquals(ldt, DateTimeUtil.toLocalDateTime(instant));
    }

    @Test
    @DisplayName("验证空安全：各种互转方法传入 null 时保底返回 null")
    void shouldReturnNullWhenInputsAreNull() {
        assertNull(DateTimeUtil.toLocalDateTime((Date) null));
        assertNull(DateTimeUtil.toLocalDateTime((Long) null));
        assertNull(DateTimeUtil.toLocalDateTime((Instant) null));

        assertNull(DateTimeUtil.toLocalDate((Date) null));
        assertNull(DateTimeUtil.toLocalDate((Long) null));

        assertNull(DateTimeUtil.toDate((LocalDateTime) null));
        assertNull(DateTimeUtil.toDate((LocalDate) null));
        assertNull(DateTimeUtil.toDate((Long) null));
        assertNull(DateTimeUtil.toDate((Instant) null));

        assertNull(DateTimeUtil.toEpochMilli((LocalDateTime) null));
        assertNull(DateTimeUtil.toEpochMilli((LocalDate) null));
        assertNull(DateTimeUtil.toEpochMilli((Date) null));
        assertNull(DateTimeUtil.toEpochMilli((Instant) null));

        assertNull(DateTimeUtil.toInstant((LocalDateTime) null));
        assertNull(DateTimeUtil.toInstant((Date) null));
        assertNull(DateTimeUtil.toInstant((Long) null));
    }

    @Test
    @DisplayName("验证当天起始与结束时间 (beginOfDay / endOfDay)")
    void shouldCalculateBeginAndEndOfDayCorrectly() {
        LocalDateTime dateTime = LocalDateTime.of(2026, 10, 18, 14, 25, 30, 500);

        LocalDateTime begin = DateTimeUtil.beginOfDay(dateTime);
        assertEquals(LocalTime.MIN, begin.toLocalTime());
        assertEquals(dateTime.toLocalDate(), begin.toLocalDate());

        LocalDateTime end = DateTimeUtil.endOfDay(dateTime);
        assertEquals(LocalTime.MAX, end.toLocalTime());
        assertEquals(dateTime.toLocalDate(), end.toLocalDate());

        // LocalDate 天首天尾
        LocalDate date = LocalDate.of(2026, 10, 18);
        assertEquals(date.atStartOfDay(), DateTimeUtil.beginOfDay(date));
        assertEquals(date.atTime(LocalTime.MAX), DateTimeUtil.endOfDay(date));

        // Date 天首天尾
        Date dateObj = DateTimeUtil.toDate(dateTime);
        Date beginDate = DateTimeUtil.beginOfDay(dateObj);
        Date endDate = DateTimeUtil.endOfDay(dateObj);
        assertEquals(LocalTime.MIN.getHour(), DateTimeUtil.toLocalDateTime(beginDate).getHour());
        assertEquals(LocalTime.MIN.getMinute(), DateTimeUtil.toLocalDateTime(beginDate).getMinute());
        assertEquals(LocalTime.MAX.getHour(), DateTimeUtil.toLocalDateTime(endDate).getHour());
        assertEquals(LocalTime.MAX.getMinute(), DateTimeUtil.toLocalDateTime(endDate).getMinute());

        // 今日起止
        assertNotNull(DateTimeUtil.beginOfToday());
        assertNotNull(DateTimeUtil.endOfToday());
        assertNotNull(DateTimeUtil.beginOfTodayDate());
        assertNotNull(DateTimeUtil.endOfTodayDate());

        // 空入参防御
        assertNull(DateTimeUtil.beginOfDay((LocalDateTime) null));
        assertNull(DateTimeUtil.endOfDay((LocalDateTime) null));
        assertNull(DateTimeUtil.beginOfDay((LocalDate) null));
        assertNull(DateTimeUtil.endOfDay((LocalDate) null));
        assertNull(DateTimeUtil.beginOfDay((Date) null));
        assertNull(DateTimeUtil.endOfDay((Date) null));
    }

    @Test
    @DisplayName("验证标准日期时间格式化与解析")
    void shouldFormatAndParseDateTimeCleanly() {
        LocalDateTime ldt = LocalDateTime.of(2026, 10, 18, 20, 15, 30);

        // 标准格式
        String formatted = DateTimeUtil.format(ldt);
        assertEquals("2026-10-18 20:15:30", formatted);

        LocalDateTime parsed = DateTimeUtil.parseDateTime(formatted);
        assertEquals(ldt, parsed);

        // 自定义模式
        String customFormatted = DateTimeUtil.format(ldt, DatePattern.PURE_DATETIME_PATTERN);
        assertEquals("20261018201530", customFormatted);

        LocalDateTime customParsed = DateTimeUtil.parseDateTime("20261018201530", DatePattern.PURE_DATETIME_PATTERN);
        assertEquals(ldt, customParsed);

        // LocalDate 格式化与解析
        LocalDate localDate = LocalDate.of(2026, 10, 18);
        String dateFormatted = DateTimeUtil.format(localDate);
        assertEquals("2026-10-18", dateFormatted);
        assertEquals(localDate, DateTimeUtil.parseDate(dateFormatted));

        // Date 格式化
        Date date = DateTimeUtil.toDate(ldt);
        assertEquals("2026-10-18 20:15:30", DateTimeUtil.format(date));
        assertEquals("20261018201530", DateTimeUtil.format(date, DatePattern.PURE_DATETIME_PATTERN));

        // 空安全防御
        assertNull(DateTimeUtil.format((LocalDateTime) null));
        assertNull(DateTimeUtil.format((LocalDate) null));
        assertNull(DateTimeUtil.format((Date) null));
        assertNull(DateTimeUtil.parseDateTime(null));
        assertNull(DateTimeUtil.parseDateTime("   "));
        assertNull(DateTimeUtil.parseDate(null));
        assertNull(DateTimeUtil.parseDate("   "));
    }

    @Test
    @DisplayName("验证时间跨度与间隔计算 (between)")
    void shouldCalculateBetweenDurationAndPeriods() {
        LocalDateTime t1 = LocalDateTime.of(2026, 10, 18, 10, 0, 0);
        LocalDateTime t2 = LocalDateTime.of(2026, 10, 20, 15, 30, 45);

        assertEquals(2L, DateTimeUtil.betweenDays(t1, t2));
        assertEquals(53L, DateTimeUtil.betweenHours(t1, t2));
        assertEquals(3210L, DateTimeUtil.betweenMinutes(t1, t2));
        assertEquals(192645L, DateTimeUtil.betweenSeconds(t1, t2));
        assertEquals(192645000L, DateTimeUtil.betweenMillis(t1, t2));

        Duration duration = DateTimeUtil.betweenDuration(t1, t2);
        assertEquals(2, duration.toDays());
        assertEquals(53, duration.toHours());

        LocalDate d1 = LocalDate.of(2025, 5, 10);
        LocalDate d2 = LocalDate.of(2026, 10, 18);
        Period period = DateTimeUtil.betweenPeriod(d1, d2);
        assertEquals(1, period.getYears());
        assertEquals(5, period.getMonths());
        assertEquals(8, period.getDays());

        // 空输入保底返回 0 或 ZERO
        assertEquals(0L, DateTimeUtil.between(null, t2, ChronoUnit.DAYS));
        assertEquals(0L, DateTimeUtil.betweenDays(t1, null));
        assertEquals(0L, DateTimeUtil.betweenHours(null, null));
        assertEquals(Duration.ZERO, DateTimeUtil.betweenDuration(null, t2));
        assertEquals(Period.ZERO, DateTimeUtil.betweenPeriod(null, d2));
    }
}
