package io.github.atengk.boot.core.util;

import io.github.atengk.boot.core.constant.DatePattern;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.Period;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.time.temporal.Temporal;
import java.util.Date;
import java.util.Objects;

/**
 * 纯 JDK 17 原生日期时间互转与跨度计算门面
 * 提供 LocalDateTime、LocalDate、Date 与时间戳三者无损互转，绑定系统时区，支持天首天尾与时间跨度计算
 *
 * @author Ateng
 * @since 2026-10-18
 */
public final class DateTimeUtil {

    /**
     * 默认绑定的系统时区标识
     */
    private static final ZoneId DEFAULT_ZONE = ZoneId.systemDefault();

    private DateTimeUtil() {
    }

    /**
     * 获取默认绑定的系统时区
     *
     * @return 当前 JVM 默认系统时区
     */
    public static ZoneId getDefaultZoneId() {
        return DEFAULT_ZONE;
    }

    // ==================== 核心互转：Date / 时间戳 -> LocalDateTime / LocalDate ====================

    /**
     * 将 Date 转换为 LocalDateTime（采用系统默认时区）
     *
     * @param date 待转换 Date 对象
     * @return LocalDateTime 实例；入参为 null 时安全返回 null
     */
    public static LocalDateTime toLocalDateTime(Date date) {
        return toLocalDateTime(date, DEFAULT_ZONE);
    }

    /**
     * 将 Date 转换为 LocalDateTime（指定时区）
     *
     * @param date   待转换 Date 对象
     * @param zoneId 指定时区，为 null 时自动回退至系统默认时区
     * @return LocalDateTime 实例；入参为 null 时安全返回 null
     */
    public static LocalDateTime toLocalDateTime(Date date, ZoneId zoneId) {
        if (date == null) {
            return null;
        }
        return date.toInstant().atZone(obtainZoneId(zoneId)).toLocalDateTime();
    }

    /**
     * 将毫秒时间戳转换为 LocalDateTime（采用系统默认时区）
     *
     * @param epochMilli 毫秒时间戳
     * @return LocalDateTime 实例；入参为 null 时安全返回 null
     */
    public static LocalDateTime toLocalDateTime(Long epochMilli) {
        return toLocalDateTime(epochMilli, DEFAULT_ZONE);
    }

    /**
     * 将毫秒时间戳转换为 LocalDateTime（指定时区）
     *
     * @param epochMilli 毫秒时间戳
     * @param zoneId     指定时区，为 null 时自动回退至系统默认时区
     * @return LocalDateTime 实例；入参为 null 时安全返回 null
     */
    public static LocalDateTime toLocalDateTime(Long epochMilli, ZoneId zoneId) {
        if (epochMilli == null) {
            return null;
        }
        return Instant.ofEpochMilli(epochMilli).atZone(obtainZoneId(zoneId)).toLocalDateTime();
    }

    /**
     * 将 Instant 转换为 LocalDateTime（采用系统默认时区）
     *
     * @param instant 待转换 Instant 对象
     * @return LocalDateTime 实例；入参为 null 时安全返回 null
     */
    public static LocalDateTime toLocalDateTime(Instant instant) {
        return toLocalDateTime(instant, DEFAULT_ZONE);
    }

    /**
     * 将 Instant 转换为 LocalDateTime（指定时区）
     *
     * @param instant 待转换 Instant 对象
     * @param zoneId  指定时区，为 null 时自动回退至系统默认时区
     * @return LocalDateTime 实例；入参为 null 时安全返回 null
     */
    public static LocalDateTime toLocalDateTime(Instant instant, ZoneId zoneId) {
        if (instant == null) {
            return null;
        }
        return instant.atZone(obtainZoneId(zoneId)).toLocalDateTime();
    }

    /**
     * 将 Date 转换为 LocalDate（采用系统默认时区）
     *
     * @param date 待转换 Date 对象
     * @return LocalDate 实例；入参为 null 时安全返回 null
     */
    public static LocalDate toLocalDate(Date date) {
        return toLocalDate(date, DEFAULT_ZONE);
    }

    /**
     * 将 Date 转换为 LocalDate（指定时区）
     *
     * @param date   待转换 Date 对象
     * @param zoneId 指定时区，为 null 时自动回退至系统默认时区
     * @return LocalDate 实例；入参为 null 时安全返回 null
     */
    public static LocalDate toLocalDate(Date date, ZoneId zoneId) {
        if (date == null) {
            return null;
        }
        return date.toInstant().atZone(obtainZoneId(zoneId)).toLocalDate();
    }

    /**
     * 将毫秒时间戳转换为 LocalDate（采用系统默认时区）
     *
     * @param epochMilli 毫秒时间戳
     * @return LocalDate 实例；入参为 null 时安全返回 null
     */
    public static LocalDate toLocalDate(Long epochMilli) {
        return toLocalDate(epochMilli, DEFAULT_ZONE);
    }

    /**
     * 将毫秒时间戳转换为 LocalDate（指定时区）
     *
     * @param epochMilli 毫秒时间戳
     * @param zoneId     指定时区，为 null 时自动回退至系统默认时区
     * @return LocalDate 实例；入参为 null 时安全返回 null
     */
    public static LocalDate toLocalDate(Long epochMilli, ZoneId zoneId) {
        if (epochMilli == null) {
            return null;
        }
        return Instant.ofEpochMilli(epochMilli).atZone(obtainZoneId(zoneId)).toLocalDate();
    }

    // ==================== 核心互转：LocalDateTime / LocalDate -> Date / 时间戳 ====================

    /**
     * 将 LocalDateTime 转换为 Date（采用系统默认时区）
     *
     * @param localDateTime 待转换 LocalDateTime 对象
     * @return Date 实例；入参为 null 时安全返回 null
     */
    public static Date toDate(LocalDateTime localDateTime) {
        return toDate(localDateTime, DEFAULT_ZONE);
    }

    /**
     * 将 LocalDateTime 转换为 Date（指定时区）
     *
     * @param localDateTime 待转换 LocalDateTime 对象
     * @param zoneId        指定时区，为 null 时自动回退至系统默认时区
     * @return Date 实例；入参为 null 时安全返回 null
     */
    public static Date toDate(LocalDateTime localDateTime, ZoneId zoneId) {
        if (localDateTime == null) {
            return null;
        }
        return Date.from(localDateTime.atZone(obtainZoneId(zoneId)).toInstant());
    }

    /**
     * 将 LocalDate 转换为当天起始 00:00:00 的 Date（采用系统默认时区）
     *
     * @param localDate 待转换 LocalDate 对象
     * @return Date 实例；入参为 null 时安全返回 null
     */
    public static Date toDate(LocalDate localDate) {
        return toDate(localDate, DEFAULT_ZONE);
    }

    /**
     * 将 LocalDate 转换为当天起始 00:00:00 的 Date（指定时区）
     *
     * @param localDate 待转换 LocalDate 对象
     * @param zoneId    指定时区，为 null 时自动回退至系统默认时区
     * @return Date 实例；入参为 null 时安全返回 null
     */
    public static Date toDate(LocalDate localDate, ZoneId zoneId) {
        if (localDate == null) {
            return null;
        }
        return Date.from(localDate.atStartOfDay(obtainZoneId(zoneId)).toInstant());
    }

    /**
     * 将毫秒时间戳转换为 Date
     *
     * @param epochMilli 毫秒时间戳
     * @return Date 实例；入参为 null 时安全返回 null
     */
    public static Date toDate(Long epochMilli) {
        if (epochMilli == null) {
            return null;
        }
        return new Date(epochMilli);
    }

    /**
     * 将 Instant 转换为 Date
     *
     * @param instant 待转换 Instant 对象
     * @return Date 实例；入参为 null 时安全返回 null
     */
    public static Date toDate(Instant instant) {
        if (instant == null) {
            return null;
        }
        return Date.from(instant);
    }

    /**
     * 获取 LocalDateTime 对应的毫秒时间戳（采用系统默认时区）
     *
     * @param localDateTime 待转换 LocalDateTime 对象
     * @return 毫秒时间戳；入参为 null 时安全返回 null
     */
    public static Long toEpochMilli(LocalDateTime localDateTime) {
        return toEpochMilli(localDateTime, DEFAULT_ZONE);
    }

    /**
     * 获取 LocalDateTime 对应的毫秒时间戳（指定时区）
     *
     * @param localDateTime 待转换 LocalDateTime 对象
     * @param zoneId        指定时区，为 null 时自动回退至系统默认时区
     * @return 毫秒时间戳；入参为 null 时安全返回 null
     */
    public static Long toEpochMilli(LocalDateTime localDateTime, ZoneId zoneId) {
        if (localDateTime == null) {
            return null;
        }
        return localDateTime.atZone(obtainZoneId(zoneId)).toInstant().toEpochMilli();
    }

    /**
     * 获取 LocalDate 对应当天起始 00:00:00 的毫秒时间戳（采用系统默认时区）
     *
     * @param localDate 待转换 LocalDate 对象
     * @return 毫秒时间戳；入参为 null 时安全返回 null
     */
    public static Long toEpochMilli(LocalDate localDate) {
        return toEpochMilli(localDate, DEFAULT_ZONE);
    }

    /**
     * 获取 LocalDate 对应当天起始 00:00:00 的毫秒时间戳（指定时区）
     *
     * @param localDate 待转换 LocalDate 对象
     * @param zoneId    指定时区，为 null 时自动回退至系统默认时区
     * @return 毫秒时间戳；入参为 null 时安全返回 null
     */
    public static Long toEpochMilli(LocalDate localDate, ZoneId zoneId) {
        if (localDate == null) {
            return null;
        }
        return localDate.atStartOfDay(obtainZoneId(zoneId)).toInstant().toEpochMilli();
    }

    /**
     * 获取 Date 对应的毫秒时间戳
     *
     * @param date 待提取 Date 对象
     * @return 毫秒时间戳；入参为 null 时安全返回 null
     */
    public static Long toEpochMilli(Date date) {
        if (date == null) {
            return null;
        }
        return date.getTime();
    }

    /**
     * 获取 Instant 对应的毫秒时间戳
     *
     * @param instant 待提取 Instant 对象
     * @return 毫秒时间戳；入参为 null 时安全返回 null
     */
    public static Long toEpochMilli(Instant instant) {
        if (instant == null) {
            return null;
        }
        return instant.toEpochMilli();
    }

    /**
     * 将 LocalDateTime 转换为 Instant（采用系统默认时区）
     *
     * @param localDateTime 待转换 LocalDateTime 对象
     * @return Instant 实例；入参为 null 时安全返回 null
     */
    public static Instant toInstant(LocalDateTime localDateTime) {
        return toInstant(localDateTime, DEFAULT_ZONE);
    }

    /**
     * 将 LocalDateTime 转换为 Instant（指定时区）
     *
     * @param localDateTime 待转换 LocalDateTime 对象
     * @param zoneId        指定时区，为 null 时自动回退至系统默认时区
     * @return Instant 实例；入参为 null 时安全返回 null
     */
    public static Instant toInstant(LocalDateTime localDateTime, ZoneId zoneId) {
        if (localDateTime == null) {
            return null;
        }
        return localDateTime.atZone(obtainZoneId(zoneId)).toInstant();
    }

    /**
     * 将 Date 转换为 Instant
     *
     * @param date 待转换 Date 对象
     * @return Instant 实例；入参为 null 时安全返回 null
     */
    public static Instant toInstant(Date date) {
        if (date == null) {
            return null;
        }
        return date.toInstant();
    }

    /**
     * 将毫秒时间戳转换为 Instant
     *
     * @param epochMilli 毫秒时间戳
     * @return Instant 实例；入参为 null 时安全返回 null
     */
    public static Instant toInstant(Long epochMilli) {
        if (epochMilli == null) {
            return null;
        }
        return Instant.ofEpochMilli(epochMilli);
    }

    // ==================== 天首与天尾起止时间 ====================

    /**
     * 获取指定 LocalDateTime 所在天的起始时间（00:00:00.000000000）
     *
     * @param dateTime 指定时间
     * @return 当天起始时间；入参为 null 时安全返回 null
     */
    public static LocalDateTime beginOfDay(LocalDateTime dateTime) {
        if (dateTime == null) {
            return null;
        }
        return dateTime.with(LocalTime.MIN);
    }

    /**
     * 获取指定 LocalDateTime 所在天的结束时间（23:59:59.999999999）
     *
     * @param dateTime 指定时间
     * @return 当天结束时间；入参为 null 时安全返回 null
     */
    public static LocalDateTime endOfDay(LocalDateTime dateTime) {
        if (dateTime == null) {
            return null;
        }
        return dateTime.with(LocalTime.MAX);
    }

    /**
     * 获取指定 LocalDate 所在天的起始时间（00:00:00）
     *
     * @param date 指定日期
     * @return 当天起始时间；入参为 null 时安全返回 null
     */
    public static LocalDateTime beginOfDay(LocalDate date) {
        if (date == null) {
            return null;
        }
        return date.atStartOfDay();
    }

    /**
     * 获取指定 LocalDate 所在天的结束时间（23:59:59.999999999）
     *
     * @param date 指定日期
     * @return 当天结束时间；入参为 null 时安全返回 null
     */
    public static LocalDateTime endOfDay(LocalDate date) {
        if (date == null) {
            return null;
        }
        return date.atTime(LocalTime.MAX);
    }

    /**
     * 获取指定 Date 所在天的起始时间 Date（采用系统默认时区）
     *
     * @param date 指定日期
     * @return 当天起始 Date；入参为 null 时安全返回 null
     */
    public static Date beginOfDay(Date date) {
        if (date == null) {
            return null;
        }
        LocalDateTime start = toLocalDateTime(date).with(LocalTime.MIN);
        return toDate(start);
    }

    /**
     * 获取指定 Date 所在天的结束时间 Date（采用系统默认时区）
     *
     * @param date 指定日期
     * @return 当天结束 Date；入参为 null 时安全返回 null
     */
    public static Date endOfDay(Date date) {
        if (date == null) {
            return null;
        }
        LocalDateTime end = toLocalDateTime(date).with(LocalTime.MAX);
        return toDate(end);
    }

    /**
     * 获取今天起始时间（00:00:00）
     *
     * @return 今天的起始 LocalDateTime
     */
    public static LocalDateTime beginOfToday() {
        return LocalDate.now().atStartOfDay();
    }

    /**
     * 获取今天结束时间（23:59:59.999999999）
     *
     * @return 今天的结束 LocalDateTime
     */
    public static LocalDateTime endOfToday() {
        return LocalDate.now().atTime(LocalTime.MAX);
    }

    /**
     * 获取今天起始时间的 Date 实例（采用系统默认时区）
     *
     * @return 今天的起始 Date
     */
    public static Date beginOfTodayDate() {
        return toDate(beginOfToday());
    }

    /**
     * 获取今天结束时间的 Date 实例（采用系统默认时区）
     *
     * @return 今天的结束 Date
     */
    public static Date endOfTodayDate() {
        return toDate(endOfToday());
    }

    // ==================== 格式化与解析 ====================

    /**
     * 使用标准格式 (yyyy-MM-dd HH:mm:ss) 格式化 LocalDateTime
     *
     * @param dateTime 待格式化时间
     * @return 格式化后的字符串；入参为 null 时安全返回 null
     */
    public static String format(LocalDateTime dateTime) {
        if (dateTime == null) {
            return null;
        }
        return DatePattern.NORM_DATETIME_FORMATTER.format(dateTime);
    }

    /**
     * 使用指定格式模式格式化 LocalDateTime
     *
     * @param dateTime 待格式化时间
     * @param pattern  格式化模式，如 yyyyMMddHHmmss
     * @return 格式化后的字符串；入参为 null 时安全返回 null
     */
    public static String format(LocalDateTime dateTime, String pattern) {
        if (dateTime == null || pattern == null || pattern.isEmpty()) {
            return null;
        }
        return DateTimeFormatter.ofPattern(pattern).format(dateTime);
    }

    /**
     * 使用指定 DateTimeFormatter 格式化 LocalDateTime
     *
     * @param dateTime  待格式化时间
     * @param formatter 格式化器
     * @return 格式化后的字符串；入参为 null 时安全返回 null
     */
    public static String format(LocalDateTime dateTime, DateTimeFormatter formatter) {
        if (dateTime == null || formatter == null) {
            return null;
        }
        return formatter.format(dateTime);
    }

    /**
     * 使用标准日期格式 (yyyy-MM-dd) 格式化 LocalDate
     *
     * @param date 待格式化日期
     * @return 格式化后的字符串；入参为 null 时安全返回 null
     */
    public static String format(LocalDate date) {
        if (date == null) {
            return null;
        }
        return DatePattern.NORM_DATE_FORMATTER.format(date);
    }

    /**
     * 使用指定格式模式格式化 LocalDate
     *
     * @param date    待格式化日期
     * @param pattern 格式化模式，如 yyyyMMdd
     * @return 格式化后的字符串；入参为 null 时安全返回 null
     */
    public static String format(LocalDate date, String pattern) {
        if (date == null || pattern == null || pattern.isEmpty()) {
            return null;
        }
        return DateTimeFormatter.ofPattern(pattern).format(date);
    }

    /**
     * 使用标准格式 (yyyy-MM-dd HH:mm:ss) 格式化 Date（采用系统默认时区）
     *
     * @param date 待格式化 Date
     * @return 格式化后的字符串；入参为 null 时安全返回 null
     */
    public static String format(Date date) {
        if (date == null) {
            return null;
        }
        return format(toLocalDateTime(date));
    }

    /**
     * 使用指定格式模式格式化 Date（采用系统默认时区）
     *
     * @param date    待格式化 Date
     * @param pattern 格式化模式
     * @return 格式化后的字符串；入参为 null 时安全返回 null
     */
    public static String format(Date date, String pattern) {
        if (date == null || pattern == null || pattern.isEmpty()) {
            return null;
        }
        return format(toLocalDateTime(date), pattern);
    }

    /**
     * 解析标准格式 (yyyy-MM-dd HH:mm:ss) 字符串为 LocalDateTime
     *
     * @param text 待解析文本
     * @return LocalDateTime 实例；入参为空时安全返回 null
     */
    public static LocalDateTime parseDateTime(String text) {
        if (text == null || text.trim().isEmpty()) {
            return null;
        }
        return LocalDateTime.parse(text.trim(), DatePattern.NORM_DATETIME_FORMATTER);
    }

    /**
     * 解析指定格式模式字符串为 LocalDateTime
     *
     * @param text    待解析文本
     * @param pattern 格式化模式
     * @return LocalDateTime 实例；入参为空时安全返回 null
     */
    public static LocalDateTime parseDateTime(String text, String pattern) {
        if (text == null || text.trim().isEmpty() || pattern == null || pattern.isEmpty()) {
            return null;
        }
        return LocalDateTime.parse(text.trim(), DateTimeFormatter.ofPattern(pattern));
    }

    /**
     * 解析指定格式化器字符串为 LocalDateTime
     *
     * @param text      待解析文本
     * @param formatter 格式化器
     * @return LocalDateTime 实例；入参为空时安全返回 null
     */
    public static LocalDateTime parseDateTime(String text, DateTimeFormatter formatter) {
        if (text == null || text.trim().isEmpty() || formatter == null) {
            return null;
        }
        return LocalDateTime.parse(text.trim(), formatter);
    }

    /**
     * 解析标准格式 (yyyy-MM-dd) 字符串为 LocalDate
     *
     * @param text 待解析文本
     * @return LocalDate 实例；入参为空时安全返回 null
     */
    public static LocalDate parseDate(String text) {
        if (text == null || text.trim().isEmpty()) {
            return null;
        }
        return LocalDate.parse(text.trim(), DatePattern.NORM_DATE_FORMATTER);
    }

    /**
     * 解析指定格式模式字符串为 LocalDate
     *
     * @param text    待解析文本
     * @param pattern 格式化模式
     * @return LocalDate 实例；入参为空时安全返回 null
     */
    public static LocalDate parseDate(String text, String pattern) {
        if (text == null || text.trim().isEmpty() || pattern == null || pattern.isEmpty()) {
            return null;
        }
        return LocalDate.parse(text.trim(), DateTimeFormatter.ofPattern(pattern));
    }

    // ==================== 时间跨度与间隔计算 ====================

    /**
     * 计算两个时间对象之间在指定单位上的跨度
     *
     * @param startInclusive 起始时间（包含）
     * @param endExclusive   结束时间（不包含）
     * @param unit           时间单位
     * @return 跨度差值；入参有 null 时安全返回 0
     */
    public static long between(Temporal startInclusive, Temporal endExclusive, ChronoUnit unit) {
        if (startInclusive == null || endExclusive == null || unit == null) {
            return 0L;
        }
        return unit.between(startInclusive, endExclusive);
    }

    /**
     * 计算两个时间对象之间的天数差
     *
     * @param startInclusive 起始时间（包含）
     * @param endExclusive   结束时间（不包含）
     * @return 天数差；入参有 null 时安全返回 0
     */
    public static long betweenDays(Temporal startInclusive, Temporal endExclusive) {
        return between(startInclusive, endExclusive, ChronoUnit.DAYS);
    }

    /**
     * 计算两个时间对象之间的小时差
     *
     * @param startInclusive 起始时间（包含）
     * @param endExclusive   结束时间（不包含）
     * @return 小时差；入参有 null 时安全返回 0
     */
    public static long betweenHours(Temporal startInclusive, Temporal endExclusive) {
        return between(startInclusive, endExclusive, ChronoUnit.HOURS);
    }

    /**
     * 计算两个时间对象之间的分钟差
     *
     * @param startInclusive 起始时间（包含）
     * @param endExclusive   结束时间（不包含）
     * @return 分钟差；入参有 null 时安全返回 0
     */
    public static long betweenMinutes(Temporal startInclusive, Temporal endExclusive) {
        return between(startInclusive, endExclusive, ChronoUnit.MINUTES);
    }

    /**
     * 计算两个时间对象之间的秒数差
     *
     * @param startInclusive 起始时间（包含）
     * @param endExclusive   结束时间（不包含）
     * @return 秒数差；入参有 null 时安全返回 0
     */
    public static long betweenSeconds(Temporal startInclusive, Temporal endExclusive) {
        return between(startInclusive, endExclusive, ChronoUnit.SECONDS);
    }

    /**
     * 计算两个时间对象之间的毫秒差
     *
     * @param startInclusive 起始时间（包含）
     * @param endExclusive   结束时间（不包含）
     * @return 毫秒差；入参有 null 时安全返回 0
     */
    public static long betweenMillis(Temporal startInclusive, Temporal endExclusive) {
        return between(startInclusive, endExclusive, ChronoUnit.MILLIS);
    }

    /**
     * 计算两个时间对象之间的 Duration 时间跨度
     *
     * @param startInclusive 起始时间（包含）
     * @param endExclusive   结束时间（不包含）
     * @return Duration 实例；入参有 null 时返回 Duration.ZERO
     */
    public static Duration betweenDuration(Temporal startInclusive, Temporal endExclusive) {
        if (startInclusive == null || endExclusive == null) {
            return Duration.ZERO;
        }
        return Duration.between(startInclusive, endExclusive);
    }

    /**
     * 计算两个日期之间的 Period 跨度（年/月/日）
     *
     * @param startDateInclusive 起始日期（包含）
     * @param endDateExclusive   结束日期（不包含）
     * @return Period 实例；入参有 null 时返回 Period.ZERO
     */
    public static Period betweenPeriod(LocalDate startDateInclusive, LocalDate endDateExclusive) {
        if (startDateInclusive == null || endDateExclusive == null) {
            return Period.ZERO;
        }
        return Period.between(startDateInclusive, endDateExclusive);
    }

    private static ZoneId obtainZoneId(ZoneId zoneId) {
        return zoneId != null ? zoneId : DEFAULT_ZONE;
    }
}
