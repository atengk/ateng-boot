package io.github.atengk.boot.sample.core.service;

import io.github.atengk.boot.core.model.Pair;
import io.github.atengk.boot.core.model.Triple;
import io.github.atengk.boot.core.util.BeanUtil;
import io.github.atengk.boot.core.util.CollectionUtil;
import io.github.atengk.boot.core.util.DateTimeUtil;
import io.github.atengk.boot.core.util.FileUtil;
import io.github.atengk.boot.core.util.JsonUtil;
import io.github.atengk.boot.core.util.LambdaUtil;
import io.github.atengk.boot.core.util.MoreCollectors;
import io.github.atengk.boot.core.util.RandomUtil;
import io.github.atengk.boot.core.util.TreeUtil;
import io.github.atengk.boot.core.util.ValidatorUtil;
import io.github.atengk.boot.core.validation.ValidList;
import io.github.atengk.boot.sample.core.model.SampleMenuNodeDTO;
import io.github.atengk.boot.sample.core.model.SampleUserDTO;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

/**
 * Core 场景三：数据处理、校验与工具原语服务
 * 演示并自验 JsonUtil、CollectionUtil 分批、TreeUtil 树形组装与剪枝、ValidatorUtil 校验、BeanUtil、LambdaUtil、FileUtil、DateTimeUtil 与 RandomUtil
 *
 * @author Ateng
 * @since 2026-10-19
 */
@Slf4j
@Service
public class CoreDataProcessingScenarioService {

    /**
     * 1. 验证 JsonUtil 泛型解析与序列化门面
     *
     * @param user 用户对象
     * @return 序列化后的 JSON 字符串与反序列化还原的对象
     */
    public Pair<String, SampleUserDTO> processJson(SampleUserDTO user) {
        String json = JsonUtil.toJsonString(user);
        SampleUserDTO parsed = JsonUtil.parseObject(json, SampleUserDTO.class);
        log.info("JsonUtil 序列化与反序列化: json={}, parsedId={}", json, parsed.getId());
        return Pair.of(json, parsed);
    }

    /**
     * 2. 验证 CollectionUtil 基于 subList 原生视图的零内存拷贝分批切片（防 OOM 批量流转）
     *
     * @param items     输入列表
     * @param batchSize 批次大小
     * @return 切分后的批次集合
     */
    public <T> List<List<T>> partitionList(List<T> items, int batchSize) {
        List<List<T>> partitions = CollectionUtil.partition(items, batchSize);
        log.info("CollectionUtil 分批切片: total={}, batchSize={}, partitionCount={}",
                items != null ? items.size() : 0, batchSize, partitions.size());
        return partitions;
    }

    /**
     * 3. 验证 MoreCollectors.toSafeMap 安全收集器（防 NPE 与 DuplicateKey 覆盖）
     *
     * @param users 用户列表
     * @return ID 到 Email 的映射 Map（允许 Email 为 null）
     */
    public Map<Long, String> collectToSafeMap(List<SampleUserDTO> users) {
        Map<Long, String> safeMap = users.stream()
                .collect(MoreCollectors.toSafeMap(SampleUserDTO::getId, SampleUserDTO::getEmail));
        log.info("MoreCollectors.toSafeMap 收集结果: size={}", safeMap.size());
        return safeMap;
    }

    /**
     * 4. 验证 TreeUtil O(N) 线性树形装配
     *
     * @param flatList 扁平节点集合
     * @return 完整树形根节点集合
     */
    public List<SampleMenuNodeDTO> assembleTree(List<SampleMenuNodeDTO> flatList) {
        List<SampleMenuNodeDTO> tree = TreeUtil.build(
                flatList,
                0L,
                SampleMenuNodeDTO::getId,
                SampleMenuNodeDTO::getParentId,
                SampleMenuNodeDTO::setChildren
        );
        log.info("TreeUtil 组装树完成: roots={}", tree.size());
        return tree;
    }

    /**
     * 5. 验证 TreeUtil 递归剪枝过滤（保留命中文本的节点及其祖先链路，剪除其余分支）
     *
     * @param tree    原始树根节点列表
     * @param keyword 关键词
     * @return 剪枝过滤后的树根列表
     */
    public List<SampleMenuNodeDTO> filterTree(List<SampleMenuNodeDTO> tree, String keyword) {
        List<SampleMenuNodeDTO> filtered = TreeUtil.filter(
                tree,
                node -> node.getName() != null && node.getName().contains(keyword),
                SampleMenuNodeDTO::getChildren,
                SampleMenuNodeDTO::setChildren
        );
        log.info("TreeUtil 剪枝过滤完成: keyword={}, roots={}", keyword, filtered.size());
        return filtered;
    }

    /**
     * 5. 验证 ValidatorUtil 高频格式编程式断言与整对象快速失败校验
     *
     * @param user 待校验用户对象
     * @param groups 可选分组
     */
    public void validateUser(SampleUserDTO user, Class<?>... groups) {
        ValidatorUtil.validate(user, groups);
        log.info("ValidatorUtil 校验通过: id={}", user.getId());
    }

    /**
     * 6. 验证 ValidList 批量包装器级联校验
     *
     * @param validList 批量用户列表
     */
    public void validateBatchUsers(ValidList<SampleUserDTO> validList) {
        ValidatorUtil.validate(validList);
        log.info("ValidList 批量校验通过: size={}", validList.size());
    }

    /**
     * 7. 验证 BeanUtil 自动实例化拷贝、批量拷贝与非空合并
     *
     * @param source 源数据
     * @return 拷贝后的目标对象
     */
    public SampleUserDTO copyUser(SampleUserDTO source) {
        SampleUserDTO target = BeanUtil.copy(source, SampleUserDTO.class);
        log.info("BeanUtil 属性拷贝完成: sourceId={}, targetId={}", source.getId(), target.getId());
        return target;
    }

    /**
     * 8. 验证 LambdaUtil 强类型方法引用解析推导 JavaBean 字段名
     *
     * @return 推导出的字段名
     */
    public String resolveFieldName() {
        String fieldName = LambdaUtil.getFieldName(SampleUserDTO::getName);
        log.info("LambdaUtil 解析 SampleUserDTO::getName -> {}", fieldName);
        return fieldName;
    }

    /**
     * 9. 验证 FileUtil 路径穿越防御清洗、扩展名提取与容量格式化
     *
     * @param rawFilename 原始文件名
     * @param bytes       文件字节数
     * @return Triple<String(清洗后文件名), String(扩展名), String(格式化容量)>
     */
    public Triple<String, String, String> processFileMetadata(String rawFilename, long bytes) {
        String cleanName = FileUtil.cleanFilename(rawFilename);
        String extName = FileUtil.getExtName(rawFilename);
        String formattedSize = FileUtil.formatSize(bytes);

        log.info("FileUtil 清洗与格式化: raw={}, clean={}, ext={}, size={}",
                rawFilename, cleanName, extName, formattedSize);
        return Triple.of(cleanName, extName, formattedSize);
    }

    /**
     * 10. 验证 DateTimeUtil 无损互转、跨度计算与 RandomUtil 无锁安全随机
     *
     * @param ldt 待处理时间
     * @return Pair<String(格式化时间), String(6位随机短信验证码)>
     */
    public Pair<String, String> processDateTimeAndRandom(LocalDateTime ldt) {
        String formattedDate = DateTimeUtil.format(ldt);
        String smsCode = RandomUtil.randomNumbers(6);

        log.info("DateTimeUtil 与 RandomUtil: formattedDate={}, smsCode={}", formattedDate, smsCode);
        return Pair.of(formattedDate, smsCode);
    }
}
