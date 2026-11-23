package io.github.atengk.boot.excel.model;

import java.io.Serializable;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 异构多工作表流式读取聚合结果容器
 * <p>
 * 存储单次流式读取任务中解析出的多 Sheet 强类型数据集合，支持按 0-based 表索引与表名称双向强类型检索。
 * 遵循空安全保底契约，查询不存在的 Sheet 数据时保底返回不可变空列表，绝不返回 null。
 * </p>
 *
 * @author Ateng
 * @since 2026-10-28
 */
public class ExcelMultiSheetResult implements Serializable {

    private static final long serialVersionUID = 1L;

    /**
     * 按 Sheet 索引（0-based）存储的行数据字典
     */
    private final Map<Integer, List<?>> indexDataMap = new LinkedHashMap<>();

    /**
     * 按 Sheet 名称存储的行数据字典
     */
    private final Map<String, List<?>> nameDataMap = new LinkedHashMap<>();

    /**
     * 各 Sheet 实际解析数据行数统计字典（包含索引与名称两种寻址键）
     */
    private final Map<String, Integer> rowCountMap = new LinkedHashMap<>();

    /**
     * 按 Sheet 索引（0-based）存储的校验错误行字典
     */
    private final Map<Integer, List<ExcelErrorRow>> indexErrorMap = new LinkedHashMap<>();

    /**
     * 按 Sheet 名称存储的校验错误行字典
     */
    private final Map<String, List<ExcelErrorRow>> nameErrorMap = new LinkedHashMap<>();

    /**
     * 全工作簿聚合校验错误行列表（保持读取顺序）
     */
    private final List<ExcelErrorRow> allErrors = new ArrayList<>();

    /**
     * 全表累计解析总数据行数（包含聚合读取与流式分批读取）
     */
    private int totalRowCount = 0;

    /**
     * 是否因任一工作表错误行数达到熔断上限而触发截断
     */
    private boolean circuitBroken = false;

    public ExcelMultiSheetResult() {
    }

    /**
     * 根据工作表索引（0-based）获取强类型行数据列表
     *
     * @param sheetNo 工作表索引（从 0 开始）
     * @param clazz   目标实体类型 Class
     * @param <T>     实体泛型
     * @return 强类型实体数据列表（不存在时保底返回空列表，绝不返回 null）
     */
    @SuppressWarnings("unchecked")
    public <T> List<T> getSheetData(int sheetNo, Class<T> clazz) {
        List<?> data = indexDataMap.get(sheetNo);
        if (data == null || data.isEmpty()) {
            return Collections.emptyList();
        }
        return (List<T>) data;
    }

    /**
     * 根据工作表名称获取强类型行数据列表
     *
     * @param sheetName 工作表名称
     * @param clazz     目标实体类型 Class
     * @param <T>       实体泛型
     * @return 强类型实体数据列表（不存在时保底返回空列表，绝不返回 null）
     */
    @SuppressWarnings("unchecked")
    public <T> List<T> getSheetData(String sheetName, Class<T> clazz) {
        if (sheetName == null) {
            return Collections.emptyList();
        }
        List<?> data = nameDataMap.get(sheetName);
        if (data == null || data.isEmpty()) {
            return Collections.emptyList();
        }
        return (List<T>) data;
    }

    /**
     * 根据工作表索引（0-based）获取无类型行数据列表
     *
     * @param sheetNo 工作表索引（从 0 开始）
     * @param <T>     推断泛型
     * @return 行数据列表（不存在时保底返回空列表，绝不返回 null）
     */
    @SuppressWarnings("unchecked")
    public <T> List<T> getSheetData(int sheetNo) {
        List<?> data = indexDataMap.get(sheetNo);
        if (data == null || data.isEmpty()) {
            return Collections.emptyList();
        }
        return (List<T>) data;
    }

    /**
     * 根据工作表名称获取无类型行数据列表
     *
     * @param sheetName 工作表名称
     * @param <T>       推断泛型
     * @return 行数据列表（不存在时保底返回空列表，绝不返回 null）
     */
    @SuppressWarnings("unchecked")
    public <T> List<T> getSheetData(String sheetName) {
        if (sheetName == null) {
            return Collections.emptyList();
        }
        List<?> data = nameDataMap.get(sheetName);
        if (data == null || data.isEmpty()) {
            return Collections.emptyList();
        }
        return (List<T>) data;
    }

    /**
     * 判断是否包含指定索引的工作表数据
     *
     * @param sheetNo 工作表索引（0-based）
     * @return 是否包含对应 Sheet 数据
     */
    public boolean containsSheet(int sheetNo) {
        return indexDataMap.containsKey(sheetNo);
    }

    /**
     * 判断是否包含指定名称的工作表数据
     *
     * @param sheetName 工作表名称
     * @return 是否包含对应 Sheet 数据
     */
    public boolean containsSheet(String sheetName) {
        return sheetName != null && nameDataMap.containsKey(sheetName);
    }

    /**
     * 获取已解析的工作表总数
     *
     * @return 工作表总数
     */
    public int getSheetCount() {
        return indexDataMap.size();
    }

    /**
     * 判断聚合结果是否为空（总行数为 0）
     *
     * @return 是否为空
     */
    public boolean isEmpty() {
        return totalRowCount == 0;
    }

    /**
     * 获取全表累计解析总数据行数
     *
     * @return 累计数据总行数
     */
    public int getTotalRowCount() {
        return totalRowCount;
    }

    /**
     * 获取指定索引工作表的数据行数
     *
     * @param sheetNo 工作表索引（0-based）
     * @return 数据行数（不存在时返回 0）
     */
    public int getRowCount(int sheetNo) {
        return rowCountMap.getOrDefault(String.valueOf(sheetNo), 0);
    }

    /**
     * 获取指定名称工作表的数据行数
     *
     * @param sheetName 工作表名称
     * @return 数据行数（不存在时返回 0）
     */
    public int getRowCount(String sheetName) {
        return sheetName != null ? rowCountMap.getOrDefault(sheetName, 0) : 0;
    }

    /**
     * 获取按索引组织的所有工作表不可变数据视图字典
     *
     * @return 索引工作表数据字典
     */
    public Map<Integer, List<?>> getIndexedData() {
        return Collections.unmodifiableMap(indexDataMap);
    }

    /**
     * 获取按表名组织的所有工作表不可变数据视图字典
     *
     * @return 名称工作表数据字典
     */
    public Map<String, List<?>> getNamedData() {
        return Collections.unmodifiableMap(nameDataMap);
    }

    /**
     * 写入聚合工作表数据并统一绑定多维度键（序号、名称、别名等），且确保总行数仅精确累加一次
     *
     * @param data       行数据列表
     * @param sheetNos   需要绑定的所有工作表索引
     * @param sheetNames 需要绑定的所有工作表名称
     */
    public void recordSheetData(List<?> data, Iterable<Integer> sheetNos, Iterable<String> sheetNames) {
        List<?> safeData = (data != null && !data.isEmpty()) ? Collections.unmodifiableList(data) : Collections.emptyList();
        int size = safeData.size();
        if (sheetNos != null) {
            for (Integer sheetNo : sheetNos) {
                if (sheetNo != null) {
                    indexDataMap.put(sheetNo, safeData);
                    rowCountMap.put(String.valueOf(sheetNo), size);
                }
            }
        }
        if (sheetNames != null) {
            for (String sheetName : sheetNames) {
                if (sheetName != null && !sheetName.isBlank()) {
                    nameDataMap.put(sheetName, safeData);
                    rowCountMap.put(sheetName, size);
                }
            }
        }
        this.totalRowCount += size;
    }

    /**
     * 记录流式分批读取的工作表行数并统一绑定多维度键，且确保总行数仅精确累加一次
     *
     * @param count      流式读取行数
     * @param sheetNos   需要绑定的所有工作表索引
     * @param sheetNames 需要绑定的所有工作表名称
     */
    public void recordStreamedRowCount(int count, Iterable<Integer> sheetNos, Iterable<String> sheetNames) {
        if (sheetNos != null) {
            for (Integer sheetNo : sheetNos) {
                if (sheetNo != null) {
                    rowCountMap.put(String.valueOf(sheetNo), count);
                    indexDataMap.putIfAbsent(sheetNo, Collections.emptyList());
                }
            }
        }
        if (sheetNames != null) {
            for (String sheetName : sheetNames) {
                if (sheetName != null && !sheetName.isBlank()) {
                    rowCountMap.put(sheetName, count);
                    nameDataMap.putIfAbsent(sheetName, Collections.emptyList());
                }
            }
        }
        this.totalRowCount += count;
    }

    /**
     * 写入单表校验错误列表并统一绑定多维度键（序号、名称、别名等），同时汇总至全工作簿错误集合
     *
     * @param errors     当前工作表捕获的结构化错误行列表
     * @param sheetNos   需要绑定的所有工作表索引
     * @param sheetNames 需要绑定的所有工作表名称
     */
    public void recordSheetErrors(List<ExcelErrorRow> errors, Iterable<Integer> sheetNos, Iterable<String> sheetNames) {
        List<ExcelErrorRow> safeErrors = (errors != null && !errors.isEmpty())
                ? Collections.unmodifiableList(new ArrayList<>(errors))
                : Collections.emptyList();
        if (sheetNos != null) {
            for (Integer sheetNo : sheetNos) {
                if (sheetNo != null) {
                    indexErrorMap.put(sheetNo, safeErrors);
                }
            }
        }
        if (sheetNames != null) {
            for (String sheetName : sheetNames) {
                if (sheetName != null && !sheetName.isBlank()) {
                    nameErrorMap.put(sheetName, safeErrors);
                }
            }
        }
        if (!safeErrors.isEmpty()) {
            this.allErrors.addAll(safeErrors);
        }
    }

    /**
     * 根据工作表索引（0-based）获取对应 Sheet 的校验错误行列表（保底返回空列表）
     *
     * @param sheetNo 工作表索引（从 0 开始）
     * @return 结构化校验错误行列表（只读，无错误时返回空列表）
     */
    public List<ExcelErrorRow> getSheetErrors(int sheetNo) {
        return indexErrorMap.getOrDefault(sheetNo, Collections.emptyList());
    }

    /**
     * 根据工作表名称获取对应 Sheet 的校验错误行列表（保底返回空列表）
     *
     * @param sheetName 工作表名称
     * @return 结构化校验错误行列表（只读，无错误时返回空列表）
     */
    public List<ExcelErrorRow> getSheetErrors(String sheetName) {
        if (sheetName == null || sheetName.isBlank()) {
            return Collections.emptyList();
        }
        return nameErrorMap.getOrDefault(sheetName, Collections.emptyList());
    }

    /**
     * 获取全工作簿所有 Sheet 聚合后的全部校验错误行列表（保底返回空列表）
     *
     * @return 全部校验错误行只读列表
     */
    public List<ExcelErrorRow> getAllErrors() {
        return Collections.unmodifiableList(allErrors);
    }

    /**
     * 判断当前多工作表读取结果中是否包含任何校验错误
     *
     * @return true 表示存在至少一条校验错误
     */
    public boolean hasErrors() {
        return !allErrors.isEmpty();
    }

    /**
     * 标记是否存在工作表因错误行数超过熔断阈值而被截断（内部受管调用）
     *
     * @param circuitBroken 是否发生错误行数熔断
     */
    public void markCircuitBroken(boolean circuitBroken) {
        if (circuitBroken) {
            this.circuitBroken = true;
        }
    }

    /**
     * 判断本次多工作表校验读取过程中是否曾触发错误行数熔断截断
     *
     * @return true 表示至少有一个工作表达到 maxErrorRows 阈值触发熔断
     */
    public boolean isCircuitBroken() {
        return circuitBroken;
    }

    /**
     * 写入聚合工作表数据并更新行数统计（内部受管调用）
     *
     * @param sheetNo   工作表索引
     * @param sheetName 工作表名称
     * @param data      行数据列表
     */
    public void addSheetData(Integer sheetNo, String sheetName, List<?> data) {
        List<?> safeData = (data != null && !data.isEmpty()) ? Collections.unmodifiableList(data) : Collections.emptyList();
        int size = safeData.size();
        if (sheetNo != null) {
            indexDataMap.put(sheetNo, safeData);
            rowCountMap.put(String.valueOf(sheetNo), size);
        }
        if (sheetName != null && !sheetName.isBlank()) {
            nameDataMap.put(sheetName, safeData);
            rowCountMap.put(sheetName, size);
        }
        this.totalRowCount += size;
    }

    /**
     * 记录流式分批读取的工作表行数统计（内部受管调用）
     *
     * @param sheetNo   工作表索引
     * @param sheetName 工作表名称
     * @param count     流式读取行数
     */
    public void addStreamedRowCount(Integer sheetNo, String sheetName, int count) {
        if (sheetNo != null) {
            rowCountMap.put(String.valueOf(sheetNo), count);
            if (!indexDataMap.containsKey(sheetNo)) {
                indexDataMap.put(sheetNo, Collections.emptyList());
            }
        }
        if (sheetName != null && !sheetName.isBlank()) {
            rowCountMap.put(sheetName, count);
            if (!nameDataMap.containsKey(sheetName)) {
                nameDataMap.put(sheetName, Collections.emptyList());
            }
        }
        this.totalRowCount += count;
    }

    @Override
    public String toString() {
        return "ExcelMultiSheetResult{" +
                "sheetCount=" + indexDataMap.size() +
                ", totalRowCount=" + totalRowCount +
                ", errorCount=" + allErrors.size() +
                ", sheets=" + rowCountMap.keySet() +
                '}';
    }
}
