package io.github.atengk.boot.sample.core.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import io.github.atengk.boot.core.concurrent.ConcurrentLruCache;
import io.github.atengk.boot.core.model.Pair;
import io.github.atengk.boot.core.model.Result;
import io.github.atengk.boot.core.model.Triple;
import io.github.atengk.boot.core.util.BatchSliceUtil;
import io.github.atengk.boot.core.util.BeanUtil;
import io.github.atengk.boot.core.util.CollectionUtil;
import io.github.atengk.boot.core.util.ConvertUtil;
import io.github.atengk.boot.core.util.DateTimeUtil;
import io.github.atengk.boot.core.util.ExceptionUtil;
import io.github.atengk.boot.core.util.FileUtil;
import io.github.atengk.boot.core.util.JsonUtil;
import io.github.atengk.boot.core.util.LambdaUtil;
import io.github.atengk.boot.core.util.MoreCollectors;
import io.github.atengk.boot.core.util.NetUtil;
import io.github.atengk.boot.core.util.RandomUtil;
import io.github.atengk.boot.core.util.SpelUtil;
import io.github.atengk.boot.core.util.StopWatch;
import io.github.atengk.boot.core.util.StringUtil;
import io.github.atengk.boot.core.util.SpringUtil;
import io.github.atengk.boot.core.util.TreeUtil;
import io.github.atengk.boot.core.util.ValidatorUtil;
import io.github.atengk.boot.core.validation.ValidList;
import io.github.atengk.boot.core.util.BusinessAssert;
import io.github.atengk.boot.sample.core.dto.BatchAndCacheRequest;
import io.github.atengk.boot.sample.core.dto.CustomerOnboardingRequest;
import io.github.atengk.boot.sample.core.dto.JsonAndBeanRequest;
import io.github.atengk.boot.sample.core.dto.SpelAndPrimitivesRequest;
import io.github.atengk.boot.sample.core.model.SampleMenuNodeDTO;
import io.github.atengk.boot.sample.core.model.SampleUserDTO;
import io.github.atengk.boot.sample.core.vo.BatchAndCacheVO;
import io.github.atengk.boot.sample.core.vo.CategoryNodeVO;
import io.github.atengk.boot.sample.core.vo.CategoryTreeVO;
import io.github.atengk.boot.sample.core.vo.CustomerPrivacyVO;
import io.github.atengk.boot.sample.core.vo.JsonAndBeanVO;
import io.github.atengk.boot.sample.core.vo.SpelAndPrimitivesVO;
import io.github.atengk.boot.sample.core.vo.StringTransformVO;
import io.github.atengk.boot.sample.core.vo.TenantCacheStatusVO;
import io.github.atengk.boot.sample.core.vo.TenantQuotaVO;
import io.github.atengk.boot.sample.core.vo.WorkdayCalcVO;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

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

    /**
     * 11. 验证 TreeUtil 高阶能力：扁平展平、面包屑路径探寻与叶子节点提取
     *
     * @param roots 树形根节点列表
     * @return Triple<List(扁平所有节点), List(面包屑路径), List(叶子节点)>
     */
    public Triple<List<SampleMenuNodeDTO>, List<SampleMenuNodeDTO>, List<SampleMenuNodeDTO>> demonstrateAdvancedTreeOperations(List<SampleMenuNodeDTO> roots) {
        List<SampleMenuNodeDTO> flattened = TreeUtil.flatten(roots, SampleMenuNodeDTO::getChildren);
        List<SampleMenuNodeDTO> breadcrumbs = TreeUtil.findPath(roots, node -> "用户导出".equals(node.getName()), SampleMenuNodeDTO::getChildren);
        List<SampleMenuNodeDTO> leafNodes = TreeUtil.getLeafNodes(roots, SampleMenuNodeDTO::getChildren);

        log.info("TreeUtil 高阶能力: flattenedSize={}, breadcrumbsSize={}, leavesSize={}",
                flattened.size(), breadcrumbs.size(), leafNodes.size());
        return Triple.of(flattened, breadcrumbs, leafNodes);
    }

    /**
     * 12. 验证 FileUtil 安全有界读取与临时文件创建
     *
     * @param payload 测试字节
     * @return Pair<byte[](安全读取字节), String(临时文件绝对路径)>
     */
    public Pair<byte[], String> demonstrateFileAndStreamSafety(byte[] payload) {
        try {
            byte[] safeBytes = FileUtil.readNBytesSafe(new ByteArrayInputStream(payload), 1024);
            File tempFile = FileUtil.createTempFile("sample-test-", ".txt");
            log.info("FileUtil 安全流与临时文件: readLen={}, tempPath={}", safeBytes.length, tempFile.getAbsolutePath());
            return Pair.of(safeBytes, tempFile.getAbsolutePath());
        } catch (IOException e) {
            throw new IllegalStateException("File IO failed", e);
        }
    }

    /**
     * 13. 验证 DateTimeUtil 工作日智能推演与跨度计算
     *
     * @param start 起始日期
     * @return Triple<Boolean(是否工作日), LocalDate(顺延3个工作日后), Long(工作日间隔)>
     */
    public Triple<Boolean, LocalDate, Long> demonstrateWorkdays(LocalDate start) {
        boolean isWorkday = DateTimeUtil.isWorkday(start);
        LocalDate plus3Workdays = DateTimeUtil.plusWorkdays(start, 3);
        long between = DateTimeUtil.betweenWorkdays(start, plus3Workdays);

        log.info("DateTimeUtil 工作日推演: start={}, isWorkday={}, plus3={}, between={}",
                start, isWorkday, plus3Workdays, between);
        return Triple.of(isWorkday, plus3Workdays, between);
    }

    /**
     * 14. 验证 JsonUtil 校验探针与树流读取
     *
     * @param json JSON字符串
     * @return Pair<Boolean(格式是否有效), JsonNode(树结构节点)>
     */
    public Pair<Boolean, JsonNode> demonstrateJsonAdvanced(String json) {
        boolean valid = JsonUtil.isValidJson(json);
        JsonNode tree = JsonUtil.readTree(json.getBytes(StandardCharsets.UTF_8));
        log.info("JsonUtil 高级特性: isValid={}, hasNode={}", valid, tree != null);
        return Pair.of(valid, tree);
    }

    /**
     * 14.1 验证 JsonUtil.deepClone 纯内存无损深拷贝（防并发脏写）
     *
     * @param source 待克隆源对象
     * @param <T>    对象类型泛型
     * @return 深度克隆后的全新对象
     */
    public <T> T demonstrateDeepClone(T source) {
        T cloned = JsonUtil.deepClone(source);
        log.info("JsonUtil.deepClone 纯内存深拷贝完成: source={}, cloned={}", source, cloned);
        return cloned;
    }

    /**
     * 15. 验证 MoreCollectors.distinctByKey 函数式去重
     *
     * @param users 输入用户列表
     * @return 按名称去重后的用户列表
     */
    public List<SampleUserDTO> demonstrateStreamDistinct(List<SampleUserDTO> users) {
        List<SampleUserDTO> distinct = users.stream()
                .filter(MoreCollectors.distinctByKey(SampleUserDTO::getName))
                .collect(Collectors.toList());
        log.info("MoreCollectors.distinctByKey 去重: before={}, after={}", users.size(), distinct.size());
        return distinct;
    }

    /**
     * 16. 验证通用新原语 (StringUtil, StopWatch, NetUtil, ConvertUtil, ExceptionUtil, SpringUtil)
     *
     * @return 原语执行结果集合
     */
    public Map<String, Object> demonstrateGeneralPrimitives() {
        Map<String, Object> results = new HashMap<>();

        // StringUtil 命名风格转换、模板格式化与安全截断
        results.put("camelCase", StringUtil.toCamelCase("user_profile_name"));
        results.put("underlineCase", StringUtil.toUnderlineCase("userProfileName"));
        results.put("formattedText", StringUtil.format("User {} role is {}", "Admin", "SuperAdmin"));
        results.put("truncatedText", StringUtil.truncate("VeryLongBusinessDescription", 10));

        // StopWatch 毫秒秒表
        StopWatch sw = new StopWatch("sampleTask");
        sw.start("step1");
        sw.stop();
        results.put("stopWatchTotalMillis", sw.getTotalTimeMillis());

        // NetUtil 本地IP
        results.put("localIp", NetUtil.getLocalIpv4());

        // ConvertUtil 安全强转
        results.put("convertedLong", ConvertUtil.toLong("9876543210", 0L));

        // ExceptionUtil 根因提取
        Exception testEx = new RuntimeException("外层异常", new IllegalArgumentException("真实根因"));
        results.put("rootCauseMessage", ExceptionUtil.getMessage(ExceptionUtil.getRootCause(testEx)));

        // SpringUtil 容器探测
        results.put("hasServiceBean", SpringUtil.containsBean("coreDataProcessingScenarioService"));

        log.info("通用新原语验证结果: {}", results);
        return results;
    }

    /**
     * 17. 验证 ConcurrentLruCache 纯原生有界 LRU 缓存（租户配额缓存容量淘汰、命中刷新与原子计算）
     *
     * @return 缓存验证结果状态 Map
     */
    public Map<String, Object> demonstrateConcurrentLruCache() {
        Map<String, Object> results = new HashMap<>();

        // 构造最大容量为 2 的租户配额 LRU 缓存
        ConcurrentLruCache<String, TenantQuotaVO> cache = new ConcurrentLruCache<>(2);
        cache.put("tenant-standard-001", new TenantQuotaVO("tenant-standard-001", "标准企业租户", 200, 50));
        cache.put("tenant-enterprise-002", new TenantQuotaVO("tenant-enterprise-002", "旗舰企业租户", 500, 120));
        results.put("initialSize", cache.size()); // 2

        // 读取 tenant-standard-001 刷新访问热度（使 tenant-enterprise-002 变为最久未访问条目）
        TenantQuotaVO tenant1 = cache.get("tenant-standard-001");
        results.put("tenant1Quota", tenant1 != null ? tenant1.getQuotaLimit() : null);

        // 写入 tenant-vip-003 触发容量超限淘汰最久未访问条目 (tenant-enterprise-002)
        cache.put("tenant-vip-003", new TenantQuotaVO("tenant-vip-003", "VIP定制租户", 1000, 0));
        results.put("afterEvictSize", cache.size()); // 2
        results.put("hasTenant1", cache.containsKey("tenant-standard-001")); // true
        results.put("hasTenant2", cache.containsKey("tenant-enterprise-002")); // false (淘汰)
        results.put("hasTenant3", cache.containsKey("tenant-vip-003")); // true

        // 原子计算写入
        TenantQuotaVO computed = cache.computeIfAbsent("tenant-computed-004", key -> new TenantQuotaVO(key, "原子算力租户", 800, 10));
        results.put("computedQuota", computed != null ? computed.getQuotaLimit() : null);

        log.info("ConcurrentLruCache 租户配额缓存演进验证结果: {}", results);
        return results;
    }

    /**
     * 18. 验证 SpelUtil 安全受限表达式求值门面（只读属性、变量绑定、方法形参与索引别名、防注入沙箱）
     *
     * @param orderId    订单编号
     * @param clientType 客户端类型
     * @param user       用户对象
     * @return SpEL 各项求值验证结果 Map
     */
    public Map<String, Object> demonstrateSpelEvaluation(Long orderId, String clientType, SampleUserDTO user) {
        Map<String, Object> results = new HashMap<>();

        // 1. 基础算术与只读对象属性访问
        Integer mathResult = SpelUtil.evaluate("100 * 2 + 50", Integer.class);
        String userName = SpelUtil.evaluate("name", user, String.class);
        results.put("mathResult", mathResult);
        results.put("userName", userName);

        // 2. 外部变量映射求值
        String varResult = SpelUtil.evaluate("#prefix + '-' + #seq", Map.of("prefix", "ORD", "seq", 9999), String.class);
        results.put("varResult", varResult);

        // 3. 基于当前方法的参数动态求值（形参名与 #p0/#a0 别名双向支持）
        try {
            java.lang.reflect.Method method = getClass().getMethod("demonstrateSpelEvaluation", Long.class, String.class, SampleUserDTO.class);
            Object[] args = new Object[]{orderId, clientType, user};

            // 形参名绑定求值
            String paramBinding = SpelUtil.evaluateMethod(method, args, "#orderId + ':' + #clientType + ':' + #user.name", String.class);
            results.put("paramBinding", paramBinding);

            // 位置别名求值
            String positionalBinding = SpelUtil.evaluateMethod(method, args, "#p0 + '-' + #a1", String.class);
            results.put("positionalBinding", positionalBinding);
        } catch (NoSuchMethodException e) {
            throw new IllegalStateException("Reflect method failed", e);
        }

        // 4. 防注入沙箱拦截验证
        boolean injectionBlocked = false;
        try {
            SpelUtil.evaluate("T(java.lang.Runtime).getRuntime().exec('calc')");
        } catch (Exception ex) {
            injectionBlocked = true;
        }
        results.put("injectionBlocked", injectionBlocked);

        log.info("SpelUtil 场景求值结果: {}", results);
        return results;
    }

    /**
     * 19. 批量客户入驻校验（JSR-303 级联校验与编程式防卫）
     *
     * @param customerList 客户列表
     * @return 成功通过校验的客户数
     */
    public int batchOnboardCustomers(ValidList<CustomerOnboardingRequest> customerList) {
        ValidatorUtil.validate(customerList);
        for (CustomerOnboardingRequest customer : customerList) {
            if (customer.getPhone() != null) {
                BusinessAssert.isTrue(ValidatorUtil.isMobile(customer.getPhone()), "客户 [{}] 手机号码格式不合法", customer.getName());
            }
            if (customer.getIdCard() != null && !customer.getIdCard().isBlank()) {
                BusinessAssert.isTrue(ValidatorUtil.isIdCard(customer.getIdCard()), "客户 [{}] 身份证号码不合法", customer.getName());
            }
            if (customer.getSocialCreditCode() != null && !customer.getSocialCreditCode().isBlank()) {
                BusinessAssert.isTrue(ValidatorUtil.isSocialCreditCode(customer.getSocialCreditCode()), "统一社会信用代码不合法");
            }
        }
        return customerList.size();
    }

    /**
     * 20. 商品类目树构建与多维全景导航业务场景
     * 验证 TreeUtil 线性装配、自定义谓词成林、递归剪枝、深度优先先序展平、面包屑路径探寻及终端叶子提取
     *
     * @param keyword        剪枝过滤关键词（可为空）
     * @param targetNodeName 面包屑导航目标节点名（为空时默认取 "5G智能手机"）
     * @return 商品类目树与全景导航视图对象
     */
    public CategoryTreeVO processCategoryTreeScenario(String keyword, String targetNodeName) {
        // 1. 构建标准商品多级类目扁平原始数据
        List<CategoryNodeVO> flatList = new ArrayList<>();
        // 数码电脑分支
        flatList.add(CategoryNodeVO.builder().id(100L).parentId(0L).name("数码电脑").sortOrder(1).build());
        flatList.add(CategoryNodeVO.builder().id(101L).parentId(100L).name("智能设备").sortOrder(1).build());
        flatList.add(CategoryNodeVO.builder().id(1011L).parentId(101L).name("5G智能手机").sortOrder(1).build());
        flatList.add(CategoryNodeVO.builder().id(1012L).parentId(101L).name("折叠屏手机").sortOrder(2).build());
        flatList.add(CategoryNodeVO.builder().id(102L).parentId(100L).name("电脑办公").sortOrder(2).build());
        flatList.add(CategoryNodeVO.builder().id(1021L).parentId(102L).name("轻薄便携本").sortOrder(1).build());
        flatList.add(CategoryNodeVO.builder().id(1022L).parentId(102L).name("高性能台式机").sortOrder(2).build());
        // 家用电器分支
        flatList.add(CategoryNodeVO.builder().id(200L).parentId(0L).name("家用电器").sortOrder(2).build());
        flatList.add(CategoryNodeVO.builder().id(201L).parentId(200L).name("大家电").sortOrder(1).build());
        flatList.add(CategoryNodeVO.builder().id(2011L).parentId(201L).name("超薄壁画电视").sortOrder(1).build());
        flatList.add(CategoryNodeVO.builder().id(2012L).parentId(201L).name("双系统风冷冰箱").sortOrder(2).build());
        // 户外运动分支
        flatList.add(CategoryNodeVO.builder().id(300L).parentId(0L).name("户外运动").sortOrder(3).build());
        flatList.add(CategoryNodeVO.builder().id(301L).parentId(300L).name("运动穿戴").sortOrder(1).build());
        flatList.add(CategoryNodeVO.builder().id(3011L).parentId(301L).name("GPS运动手表").sortOrder(1).build());

        // 2. TreeUtil.build O(N) 线性装配完整树形结构
        List<CategoryNodeVO> fullTree = TreeUtil.build(
                flatList,
                0L,
                CategoryNodeVO::getId,
                CategoryNodeVO::getParentId,
                CategoryNodeVO::setChildren
        );

        // 3. TreeUtil.filter 递归剪枝过滤（深拷贝副本以防引用破坏）
        List<CategoryNodeVO> filteredTree = Collections.emptyList();
        if (keyword != null && !keyword.isBlank()) {
            List<CategoryNodeVO> copyForFilter = JsonUtil.deepClone(fullTree);
            filteredTree = TreeUtil.filter(
                    copyForFilter,
                    node -> node.getName() != null && node.getName().contains(keyword),
                    CategoryNodeVO::getChildren,
                    CategoryNodeVO::setChildren
            );
        }

        // 4. TreeUtil.flatten 深度优先先序遍历展平
        List<CategoryNodeVO> flattened = TreeUtil.flatten(fullTree, CategoryNodeVO::getChildren);

        // 5. TreeUtil.findPath 面包屑全链路寻路
        String targetName = (targetNodeName != null && !targetNodeName.isBlank()) ? targetNodeName : "5G智能手机";
        List<CategoryNodeVO> breadcrumbPath = TreeUtil.findPath(
                fullTree,
                node -> targetName.equals(node.getName()),
                CategoryNodeVO::getChildren
        );

        // 6. TreeUtil.getLeafNodes 终端叶子节点提取
        List<CategoryNodeVO> leafCategories = TreeUtil.getLeafNodes(fullTree, CategoryNodeVO::getChildren);

        log.info("商品类目树组装完成: totalRoots={}, flattenedCount={}, leafCount={}, breadcrumbStep={}",
                fullTree.size(), flattened.size(), leafCategories.size(), breadcrumbPath.size());

        return CategoryTreeVO.builder()
                .categoryTree(fullTree)
                .filteredTree(filteredTree)
                .flattenedCategories(flattened)
                .breadcrumbPath(breadcrumbPath)
                .leafCategories(leafCategories)
                .totalCount(flattened.size())
                .leafCount(leafCategories.size())
                .build();
    }

    /**
     * 21. 大批量切片流转消费与有界租户配额缓存演进
     * 验证 BatchSliceUtil 零内存额外分配分批切片、MoreCollectors 安全流式收集、ConcurrentLruCache 租户配额淘汰与 JsonUtil.deepClone 内存真明文
     *
     * @param request 切片与缓存演进请求载荷
     * @return 业务切片与缓存状态演进视图
     */
    public BatchAndCacheVO processBatchAndCacheScenario(BatchAndCacheRequest request) {
        // 1. 验证 BatchSliceUtil 基于 subList 原生视图的零拷贝分批切片与 Consumer 流式消费
        List<String> orderCodes = request.getOrderCodes();
        int sliceSize = request.getSliceSize() != null && request.getSliceSize() > 0 ? request.getSliceSize() : 3;
        List<List<String>> slices = BatchSliceUtil.slice(orderCodes, sliceSize);

        List<String> consumedOrders = new ArrayList<>();
        BatchSliceUtil.forEachSlice(orderCodes, sliceSize, consumedOrders::addAll);

        // 2. 验证 MoreCollectors.distinctByKey 函数式去重与 toSafeMap 安全收集
        List<String> distinctOrders = orderCodes.stream()
                .filter(MoreCollectors.distinctByKey(code -> code))
                .collect(Collectors.toList());

        Map<String, Integer> safeMap = distinctOrders.stream()
                .collect(MoreCollectors.toSafeMap(code -> code, String::length));

        // 3. 验证 ConcurrentLruCache 纯原生有界 LRU 缓存演进（容量上限为 2，淘汰最久未访问租户）
        ConcurrentLruCache<String, TenantQuotaVO> tenantCache = new ConcurrentLruCache<>(2);
        // 初始写入两组租户配额条目
        tenantCache.put("tenant-standard-001", new TenantQuotaVO("tenant-standard-001", "标准企业租户", 200, 50));
        tenantCache.put("tenant-enterprise-002", new TenantQuotaVO("tenant-enterprise-002", "旗舰企业租户", 500, 120));
        int initialCacheSize = tenantCache.size(); // 2

        // 读取 tenant-standard-001 刷新热度，使 tenant-enterprise-002 成为最久未访问条目
        TenantQuotaVO activeTenant = tenantCache.get("tenant-standard-001");

        // 写入请求中的新租户触发淘汰
        TenantQuotaVO newTenant = TenantQuotaVO.builder()
                .tenantId(request.getTenantId())
                .tenantName(request.getTenantName())
                .quotaLimit(request.getQuotaLimit())
                .usedQuota(0)
                .build();
        tenantCache.put(request.getTenantId(), newTenant);

        // 验证 tenant-enterprise-002 已被物理淘汰
        boolean isEvicted = !tenantCache.containsKey("tenant-enterprise-002");

        // 原子计算 computeIfAbsent 写入
        TenantQuotaVO computedTenant = tenantCache.computeIfAbsent(
                "tenant-computed-004",
                id -> TenantQuotaVO.builder().tenantId(id).tenantName("动态计算算力租户").quotaLimit(800).usedQuota(0).build()
        );

        TenantCacheStatusVO cacheStatusVO = TenantCacheStatusVO.builder()
                .initialSize(initialCacheSize)
                .activeTenant(activeTenant)
                .evictedTenantId("tenant-enterprise-002")
                .evicted(isEvicted)
                .computedTenant(computedTenant)
                .currentCacheSize(tenantCache.size())
                .build();

        // 4. 验证 JsonUtil.deepClone 纯内存真明文深度克隆（防掩码脏数据）
        CustomerPrivacyVO privacyVO = CustomerPrivacyVO.builder()
                .customerId(9999L)
                .realName("诸葛孔明")
                .phone("13812345678")
                .fixedPhone("010-88886678")
                .idCard("110101199003072345")
                .build();
        CustomerPrivacyVO cloned = JsonUtil.deepClone(privacyVO);

        log.info("大批量切片与租户缓存执行完成: sliceCount={}, distinctCount={}, cacheSize={}, clonedPhone={}",
                slices.size(), distinctOrders.size(), tenantCache.size(), cloned.getPhone());

        return BatchAndCacheVO.builder()
                .batchCount(slices.size())
                .sliceDetails(slices)
                .distinctItemCount(distinctOrders.size())
                .cacheStatus(cacheStatusVO)
                .clonedPlaintextPhone(cloned.getPhone())
                .clonedPlaintextIdCard(cloned.getIdCard())
                .build();
    }

    /**
     * 22. 大批量切片消费端到端方法
     *
     * @param orderCodes 订单编号列表
     * @param sliceSize  分批切片大小
     * @return 分批列表
     */
    public List<List<String>> processBatchSliceOrders(List<String> orderCodes, int sliceSize) {
        List<List<String>> batches = BatchSliceUtil.slice(orderCodes, sliceSize);
        List<String> consumed = new ArrayList<>();
        BatchSliceUtil.forEachSlice(orderCodes, sliceSize, consumed::addAll);
        log.info("BatchSliceUtil 切片流转: total={}, batches={}, consumedCount={}",
                orderCodes != null ? orderCodes.size() : 0, batches.size(), consumed.size());
        return batches;
    }

    /**
     * 23. 安全受限 SpEL 表达式求值与高频通用算法原语业务场景
     * 验证 SpelUtil 只读只解沙箱与防 RCE、BeanUtil.copyIgnoreNull 局部增量合并、LambdaUtil 字段推导、DateTimeUtil 工作日、ConvertUtil、StringUtil、RandomUtil 与元组
     *
     * @param request 请求载荷
     * @return 响应视图对象
     */
    public SpelAndPrimitivesVO processSpelAndPrimitivesScenario(SpelAndPrimitivesRequest request) {
        // 1. SpelUtil 安全只读沙箱求值
        Object spelResult = null;
        if (request.getExpression() != null && !request.getExpression().isBlank()) {
            Map<String, Object> variables = new HashMap<>();
            if (request.getUser() != null) {
                variables.put("user", request.getUser());
            }
            spelResult = SpelUtil.evaluate(request.getExpression(), request.getUser(), variables, Object.class);
        }

        // 验证防注入沙箱拦截
        boolean rceBlocked = false;
        String blockedMsg = null;
        if (request.isSimulateMaliciousSpel()) {
            try {
                SpelUtil.evaluate("T(java.lang.Runtime).getRuntime().exec('calc')");
            } catch (Exception ex) {
                rceBlocked = true;
                blockedMsg = ex.getMessage();
                log.info("SpelUtil 成功阻断恶意注入: {}", ex.getMessage());
            }
        }

        // 2. BeanUtil.copyIgnoreNull 局部非空增量合并
        SampleUserDTO mergedUser = null;
        if (request.getUser() != null) {
            mergedUser = BeanUtil.copy(request.getUser(), SampleUserDTO.class);
            if (request.getPatchUser() != null) {
                BeanUtil.copyIgnoreNull(request.getPatchUser(), mergedUser);
            }
        }

        // 3. LambdaUtil 字段名推导
        String lambdaFieldName = LambdaUtil.getFieldName(SampleUserDTO::getName);

        // 4. DateTimeUtil 工作日智能推演
        LocalDate baseDate = request.getBaseDate() != null ? request.getBaseDate() : LocalDate.of(2026, 11, 6);
        boolean workday = DateTimeUtil.isWorkday(baseDate);
        LocalDate plus3Workdays = DateTimeUtil.plusWorkdays(baseDate, 3);
        long betweenDays = DateTimeUtil.betweenWorkdays(baseDate, plus3Workdays);

        WorkdayCalcVO workdayCalcVO = WorkdayCalcVO.builder()
                .baseDate(baseDate)
                .workday(workday)
                .plus3Workdays(plus3Workdays)
                .betweenDays(betweenDays)
                .build();

        // 5. ConvertUtil 安全数值容错转换
        String rawNumberStr = (request.getRawNumberStr() != null && !request.getRawNumberStr().isBlank())
                ? request.getRawNumberStr()
                : "9876543210";
        Long convertedLong = ConvertUtil.toLong(rawNumberStr, 0L);

        // 6. StringUtil 算法变换
        String rawStr = (request.getRawString() != null && !request.getRawString().isBlank())
                ? request.getRawString()
                : "order_shipping_address";
        String camelCase = StringUtil.toCamelCase(rawStr);
        String underlineCase = StringUtil.toUnderlineCase(camelCase);
        String truncatedText = StringUtil.truncate(rawStr, 10);
        String formattedText = StringUtil.format("User [{}] action is {}", "Admin", "LOGIN");

        StringTransformVO stringTransformVO = StringTransformVO.builder()
                .camelCase(camelCase)
                .underlineCase(underlineCase)
                .truncatedText(truncatedText)
                .formattedText(formattedText)
                .build();

        // 7. RandomUtil 无锁安全随机
        String randomSmsCode = RandomUtil.randomNumbers(6);

        // 8. Pair 与 Triple 不可变元组实战
        Pair<String, Integer> pair = Pair.of("AUDIT_PASS", 200);
        Triple<String, String, Long> triple = Triple.of("NODE_A", "SUCCESS", 1024L);
        String tupleDemo = "Pair=" + pair.left() + ":" + pair.right() + ", Triple=" + triple.left() + "/" + triple.middle() + "/" + triple.right();

        log.info("SpEL与高频原语执行完成: lambdaField={}, convertedLong={}, rceBlocked={}",
                lambdaFieldName, convertedLong, rceBlocked);

        return SpelAndPrimitivesVO.builder()
                .spelResult(spelResult)
                .rceBlocked(rceBlocked)
                .blockedMessage(blockedMsg)
                .lambdaFieldName(lambdaFieldName)
                .mergedUser(mergedUser)
                .workdayResult(workdayCalcVO)
                .stringTransformResult(stringTransformVO)
                .convertedLong(convertedLong)
                .randomSmsCode(randomSmsCode)
                .tupleDemo(tupleDemo)
                .spelCacheSize(SpelUtil.getCacheSize())
                .lambdaCacheSize(LambdaUtil.getCacheSize())
                .build();
    }

    /**
     * 24. 验证二进制 JSON 载荷反序列化（TypeReference/List/Map）与深拷贝/Bean 增量合并闭环
     *
     * @param request 二进制 JSON 转换与 Bean 合并请求对象
     * @return 解析还原与增量合并结果视图
     */
    public JsonAndBeanVO processJsonAndBeanScenario(JsonAndBeanRequest request) {
        BusinessAssert.notNull(request, "请求载荷不能为空");
        BusinessAssert.notEmpty(request.getUsers(), "待传输用户列表不能为空");
        BusinessAssert.notEmpty(request.getMetadata(), "扩展属性字典不能为空");

        // 1. 列表二进制往返解析
        byte[] userBytes = JsonUtil.toJsonBytes(request.getUsers());
        List<SampleUserDTO> parsedUsers = JsonUtil.parseList(userBytes, SampleUserDTO.class);

        // 2. 复杂泛型包络二进制往返解析
        byte[] envelopeBytes = JsonUtil.toJsonBytes(Result.success(request.getUsers()));
        Result<List<SampleUserDTO>> parsedEnvelope = JsonUtil.parseObject(
                envelopeBytes, new TypeReference<Result<List<SampleUserDTO>>>() {});
        int envelopeUserCount = parsedEnvelope != null && parsedEnvelope.getData() != null
                ? parsedEnvelope.getData().size()
                : 0;

        // 3. 扩展字典二进制双重载往返解析
        byte[] metadataBytes = JsonUtil.toJsonBytes(request.getMetadata());
        Map<String, String> parsedTypedMap = JsonUtil.parseMap(metadataBytes, String.class, String.class);
        Map<String, Object> parsedGenericMap = JsonUtil.parseMap(metadataBytes);

        // 4. 内存深拷贝隔离（保留内存真明文）与 BeanUtil 局部非空增量合并
        SampleUserDTO clonedFirst = JsonUtil.deepClone(request.getUsers().get(0));
        if (request.getPatchUser() != null) {
            BeanUtil.copyIgnoreNull(request.getPatchUser(), clonedFirst);
        }

        log.info("JsonAndBean 二进制载荷解析完成: userBytesLength={}, parsedCount={}, envelopeCount={}",
                userBytes.length, parsedUsers.size(), envelopeUserCount);

        return JsonAndBeanVO.builder()
                .userBytesLength(userBytes.length)
                .parsedUsers(parsedUsers)
                .genericEnvelopeUserCount(envelopeUserCount)
                .parsedTypedMap(parsedTypedMap)
                .parsedGenericMap(parsedGenericMap)
                .deepClonedFirstUser(clonedFirst)
                .build();
    }
}
