package io.github.atengk.boot.core.util;

import io.github.atengk.boot.core.model.Pair;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.function.Supplier;

/**
 * 纳秒级高精度任务耗时秒表与函数剖析门面
 * 基于 {@link System#nanoTime()} 实现，支持多分段任务（Task Splits）阶梯统计、可视化报告输出（{@link #prettyPrint()}）
 * 与闭包执行耗时函数式测算
 *
 * @author Ateng
 * @since 2026-10-26
 */
public class StopWatch {

    private final String id;
    private final List<TaskInfo> taskList = new ArrayList<>();

    private String currentTaskName;
    private long startTimeNanos;
    private TaskInfo lastTaskInfo;
    private long totalTimeNanos;
    private boolean running;

    /**
     * 创建未命名的秒表实例
     */
    public StopWatch() {
        this("");
    }

    /**
     * 创建指定标识的秒表实例
     *
     * @param id 秒表唯一业务标识
     */
    public StopWatch(String id) {
        this.id = id != null ? id : "";
    }

    /**
     * 获取秒表业务标识
     *
     * @return 业务标识
     */
    public String getId() {
        return id;
    }

    /**
     * 启动一个未命名的计时任务分段
     *
     * @throws IllegalStateException 若秒表当前已处于运行状态
     */
    public void start() {
        start("");
    }

    /**
     * 启动一个指定名称的计时任务分段
     *
     * @param taskName 任务名称
     * @throws IllegalStateException 若秒表当前已处于运行状态
     */
    public void start(String taskName) {
        if (this.running) {
            throw new IllegalStateException("秒表已在运行中，请先调用 stop() 停止当前任务: [" + this.currentTaskName + "]");
        }
        this.currentTaskName = taskName != null ? taskName : "";
        this.startTimeNanos = System.nanoTime();
        this.running = true;
    }

    /**
     * 停止当前计时任务分段
     *
     * @throws IllegalStateException 若秒表当前未处于运行状态
     */
    public void stop() {
        if (!this.running) {
            throw new IllegalStateException("秒表当前尚未启动，无法调用 stop()");
        }
        long durationNanos = System.nanoTime() - this.startTimeNanos;
        this.totalTimeNanos += durationNanos;
        this.lastTaskInfo = new TaskInfo(this.currentTaskName, durationNanos);
        this.taskList.add(this.lastTaskInfo);
        this.running = false;
        this.currentTaskName = null;
    }

    /**
     * 判断秒表当前是否处于运行状态
     *
     * @return 若正在计时返回 true，否则返回 false
     */
    public boolean isRunning() {
        return this.running;
    }

    /**
     * 获取当前正在运行的任务名称
     *
     * @return 当前任务名称，未运行时返回 null
     */
    public String currentTaskName() {
        return this.currentTaskName;
    }

    /**
     * 获取最近一个已完成任务分段的耗时纳秒数
     *
     * @return 耗时纳秒数
     * @throws IllegalStateException 若尚未执行过任何任务
     */
    public long getLastTaskTimeNanos() {
        if (this.lastTaskInfo == null) {
            throw new IllegalStateException("尚未记录任何任务耗时");
        }
        return this.lastTaskInfo.timeNanos();
    }

    /**
     * 获取最近一个已完成任务分段的耗时毫秒数
     *
     * @return 耗时毫秒数
     * @throws IllegalStateException 若尚未执行过任何任务
     */
    public long getLastTaskTimeMillis() {
        return getLastTaskTimeNanos() / 1_000_000L;
    }

    /**
     * 获取秒表累计总耗时（纳秒）
     *
     * @return 总耗时纳秒数
     */
    public long getTotalTimeNanos() {
        return this.totalTimeNanos;
    }

    /**
     * 获取秒表累计总耗时（毫秒）
     *
     * @return 总耗时毫秒数
     */
    public long getTotalTimeMillis() {
        return this.totalTimeNanos / 1_000_000L;
    }

    /**
     * 获取秒表累计总耗时（秒）
     *
     * @return 总耗时秒数
     */
    public double getTotalTimeSeconds() {
        return (double) this.totalTimeNanos / 1_000_000_000.0;
    }

    /**
     * 获取已记录的任务分段总数
     *
     * @return 任务分段数量
     */
    public int getTaskCount() {
        return this.taskList.size();
    }

    /**
     * 获取所有已记录的任务分段信息数组
     *
     * @return 任务分段数组
     */
    public TaskInfo[] getTaskInfo() {
        return this.taskList.toArray(new TaskInfo[0]);
    }

    /**
     * 输出简短的执行概要描述
     *
     * @return 概要字符串
     */
    public String shortSummary() {
        return "StopWatch '" + this.id + "': running time = " + getTotalTimeMillis() + " ms";
    }

    /**
     * 格式化输出多任务分段耗时百分比统计表格
     *
     * @return 格式化表格字符串
     */
    public String prettyPrint() {
        StringBuilder sb = new StringBuilder(shortSummary());
        sb.append('\n');
        sb.append("---------------------------------------------\n");
        sb.append("ms         %     Task name\n");
        sb.append("---------------------------------------------\n");
        for (TaskInfo task : getTaskInfo()) {
            double percent = this.totalTimeNanos > 0
                    ? ((double) task.timeNanos() / (double) this.totalTimeNanos) * 100.0
                    : 0.0;
            sb.append(String.format("%-10d %3.0f%%  %s\n", task.getTimeMillis(), percent, task.taskName()));
        }
        return sb.toString();
    }

    /**
     * 测量 Runnable 任务闭包执行耗时（毫秒）
     *
     * @param task 待测量的任务
     * @return 耗时毫秒数
     */
    public static long measureMillis(Runnable task) {
        Objects.requireNonNull(task, "task 不能为空");
        long start = System.nanoTime();
        task.run();
        return (System.nanoTime() - start) / 1_000_000L;
    }

    /**
     * 测量 Runnable 任务闭包执行耗时（纳秒）
     *
     * @param task 待测量的任务
     * @return 耗时纳秒数
     */
    public static long measureNanos(Runnable task) {
        Objects.requireNonNull(task, "task 不能为空");
        long start = System.nanoTime();
        task.run();
        return System.nanoTime() - start;
    }

    /**
     * 测量 Supplier 闭包执行结果与耗时（毫秒）
     *
     * @param task 待测量的有返回值任务
     * @param <T>  返回值泛型
     * @return 包含结果与耗时毫秒数的 Pair 载体
     */
    public static <T> Pair<T, Long> measure(Supplier<T> task) {
        Objects.requireNonNull(task, "task 不能为空");
        long start = System.nanoTime();
        T result = task.get();
        long elapsedMillis = (System.nanoTime() - start) / 1_000_000L;
        return Pair.of(result, elapsedMillis);
    }

    /**
     * 测量 Supplier 闭包执行结果与耗时（纳秒）
     *
     * @param task 待测量的有返回值任务
     * @param <T>  返回值泛型
     * @return 包含结果与耗时纳秒数的 Pair 载体
     */
    public static <T> Pair<T, Long> measureNanos(Supplier<T> task) {
        Objects.requireNonNull(task, "task 不能为空");
        long start = System.nanoTime();
        T result = task.get();
        long elapsedNanos = System.nanoTime() - start;
        return Pair.of(result, elapsedNanos);
    }

    /**
     * 单个任务分段耗时信息不可变模型
     *
     * @param taskName  任务名称
     * @param timeNanos 耗时纳秒数
     */
    public record TaskInfo(String taskName, long timeNanos) {

        /**
         * 获取耗时毫秒数
         *
         * @return 毫秒数
         */
        public long getTimeMillis() {
            return timeNanos / 1_000_000L;
        }

        /**
         * 获取耗时秒数
         *
         * @return 秒数
         */
        public double getTimeSeconds() {
            return (double) timeNanos / 1_000_000_000.0;
        }
    }
}
