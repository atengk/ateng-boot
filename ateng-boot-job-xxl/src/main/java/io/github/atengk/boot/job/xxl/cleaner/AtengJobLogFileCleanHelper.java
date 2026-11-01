package io.github.atengk.boot.job.xxl.cleaner;

import com.xxl.job.core.log.XxlJobFileAppender;
import com.xxl.job.core.thread.JobLogFileCleanThreadHelper;
import io.github.atengk.boot.core.concurrent.NamedThreadFactory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.io.IOException;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.Objects;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;

/**
 * 生产加固型 XXL-Job 调度日志文件清理辅助器
 *
 * <p>全面重构并加固官方 {@link JobLogFileCleanThreadHelper}，具备以下工业级生产特性：
 * <ol>
 *     <li><b>严格目录正则匹配</b>：仅对符合 {@code ^\d{4}-\d{2}-\d{2}$} 日期目录格式的子文件夹进行生命周期判定，主动忽略非日期文件夹与宿主隐性系统文件（如 {@code .DS_Store}、{@code desktop.ini}、临时 sentinel 文件）；</li>
 *     <li><b>防御性解析防崩保护</b>：基于标准 Java 8+ {@link LocalDate} 与 {@link DateTimeFormatter}，严格隔离非法日期解析异常，彻底杜绝清理后台线程崩溃；</li>
 *     <li><b>受管定时线程与优雅停机</b>：接入 Core 模块标准 {@link NamedThreadFactory}，使用独立受管调度线程池执行定时淘汰与平滑销毁。</li>
 * </ol>
 * </p>
 *
 * @author Ateng
 * @since 2026-11-01
 */
public class AtengJobLogFileCleanHelper extends JobLogFileCleanThreadHelper {

    private static final Logger log = LoggerFactory.getLogger(AtengJobLogFileCleanHelper.class);

    /**
     * 日志日期目录严格正则匹配：yyyy-MM-dd
     */
    public static final Pattern DATE_FOLDER_PATTERN = Pattern.compile("^\\d{4}-\\d{2}-\\d{2}$");

    private ScheduledExecutorService scheduledExecutor;

    public AtengJobLogFileCleanHelper() {
        super();
    }

    /**
     * 启动日志定时清理后台线程
     *
     * @param logRetentionDays 日志保留天数（需大于等于 1 天才启动）
     */
    @Override
    public synchronized void start(long logRetentionDays) {
        if (logRetentionDays < 1) {
            log.info("logRetentionDays < 1 ({})，跳过 XXL-Job 日志清理后台线程启动", logRetentionDays);
            return;
        }

        if (this.scheduledExecutor != null && !this.scheduledExecutor.isShutdown()) {
            return;
        }

        this.scheduledExecutor = Executors.newSingleThreadScheduledExecutor(
                new NamedThreadFactory("xxl-job-log-clean", true)
        );

        // 启动后立即执行一次清理自检，之后每隔 24 小时周期清理
        this.scheduledExecutor.scheduleWithFixedDelay(() -> {
            try {
                String logPath = XxlJobFileAppender.getLogPath();
                int deleted = clean(logPath, logRetentionDays);
                if (deleted > 0) {
                    log.info("XXL-Job 日志清理周期完成，共清理过期日期目录 {} 个", deleted);
                }
            } catch (Throwable t) {
                log.error("执行 XXL-Job 历史日志清理异常", t);
            }
        }, 0, 1, TimeUnit.DAYS);

        log.info(">>>>>>>>>>> ateng-boot xxl-job log cleaner started successfully [retentionDays: {}]", logRetentionDays);
    }

    /**
     * 安全停止日志清理后台线程
     */
    @Override
    public synchronized void stop() {
        if (this.scheduledExecutor != null) {
            this.scheduledExecutor.shutdownNow();
            try {
                if (!this.scheduledExecutor.awaitTermination(3, TimeUnit.SECONDS)) {
                    log.warn("XXL-Job 日志清理后台线程终止超时，强制中断退出");
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            } finally {
                this.scheduledExecutor = null;
            }
            log.info(">>>>>>>>>>> ateng-boot xxl-job log cleaner stopped.");
        }
    }

    /**
     * 执行单次目录清理（对标当前系统真实日期）
     *
     * @param logPath          日志根目录
     * @param logRetentionDays 保留天数
     * @return 实际清理的过期文件夹数量
     */
    public int clean(String logPath, long logRetentionDays) {
        return clean(logPath, logRetentionDays, LocalDate.now());
    }

    /**
     * 执行单次目录清理（支持指定基准参考日期，便于单测全自洽验证）
     *
     * @param logPath          日志根目录
     * @param logRetentionDays 保留天数
     * @param referenceDate    基准参考日期
     * @return 实际清理的过期文件夹数量
     */
    public int clean(String logPath, long logRetentionDays, LocalDate referenceDate) {
        if (logPath == null || logPath.isBlank() || logRetentionDays < 1) {
            return 0;
        }

        LocalDate baseDate = (referenceDate != null) ? referenceDate : LocalDate.now();
        File logDir = new File(logPath);
        if (!logDir.exists() || !logDir.isDirectory()) {
            return 0;
        }

        File[] childFiles = logDir.listFiles();
        if (childFiles == null || childFiles.length == 0) {
            return 0;
        }

        int deletedCount = 0;
        for (File child : childFiles) {
            if (child == null || !child.isDirectory()) {
                continue;
            }

            String dirName = child.getName();

            // 1. 过滤隐藏目录与操作系统元数据特殊文件
            if (dirName.startsWith(".") || "desktop.ini".equalsIgnoreCase(dirName)) {
                continue;
            }

            // 2. 严格校验 yyyy-MM-dd 日期格式，防御非日期目录引发的 ParseException
            if (!DATE_FOLDER_PATTERN.matcher(dirName).matches()) {
                continue;
            }

            // 3. 安全解析日期并判断生命周期
            LocalDate logDate;
            try {
                logDate = LocalDate.parse(dirName, DateTimeFormatter.ISO_LOCAL_DATE);
            } catch (DateTimeParseException e) {
                log.warn("无法将目录名解析为合法日期，已安全跳过: {}", dirName);
                continue;
            }

            // 4. 判断是否过期：若 logDate + logRetentionDays < baseDate，则判定为过期
            if (logDate.plusDays(logRetentionDays).isBefore(baseDate)) {
                boolean success = deleteRecursively(child);
                if (success) {
                    deletedCount++;
                    log.info("成功清理过期 XXL-Job 日志目录: {}", child.getAbsolutePath());
                } else {
                    log.warn("清理过期 XXL-Job 日志目录失败: {}", child.getAbsolutePath());
                }
            }
        }

        return deletedCount;
    }

    /**
     * 递归删除目录及其下所有文件
     *
     * @param root 待删除的根目录
     * @return 是否删除成功
     */
    private boolean deleteRecursively(File root) {
        if (root == null || !root.exists()) {
            return true;
        }

        try {
            Files.walkFileTree(root.toPath(), new SimpleFileVisitor<Path>() {
                @Override
                public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
                    Files.deleteIfExists(file);
                    return FileVisitResult.CONTINUE;
                }

                @Override
                public FileVisitResult postVisitDirectory(Path dir, IOException exc) throws IOException {
                    if (exc != null) {
                        throw exc;
                    }
                    Files.deleteIfExists(dir);
                    return FileVisitResult.CONTINUE;
                }
            });
            return true;
        } catch (Exception e) {
            log.error("递归删除日志目录发生异常: " + root.getAbsolutePath(), e);
            return false;
        }
    }

    /**
     * 获取当前调度线程池状态（主要用于测试与运维内省）
     *
     * @return 线程池是否处于活跃运行态
     */
    public synchronized boolean isRunning() {
        return this.scheduledExecutor != null && !this.scheduledExecutor.isShutdown();
    }

}
