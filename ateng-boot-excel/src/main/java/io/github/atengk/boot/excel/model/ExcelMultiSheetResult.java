package io.github.atengk.boot.excel.model;

import java.io.Serializable;
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
     * 全表累计解析总数据行数（包含聚合读取与流式分批读取）
     */
    private int totalRowCount = 0;

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
                ", sheets=" + rowCountMap.keySet() +
                '}';
    }
}
