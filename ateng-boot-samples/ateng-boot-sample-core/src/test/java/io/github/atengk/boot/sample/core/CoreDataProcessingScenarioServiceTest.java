package io.github.atengk.boot.sample.core;

import com.fasterxml.jackson.databind.JsonNode;
import io.github.atengk.boot.core.exception.BusinessException;
import io.github.atengk.boot.core.model.Pair;
import io.github.atengk.boot.core.model.Triple;
import io.github.atengk.boot.core.util.ValidatorUtil;
import io.github.atengk.boot.core.validation.ValidList;
import io.github.atengk.boot.core.validation.ValidationGroups;
import io.github.atengk.boot.sample.core.model.SampleMenuNodeDTO;
import io.github.atengk.boot.sample.core.model.SampleUserDTO;
import io.github.atengk.boot.sample.core.service.CoreDataProcessingScenarioService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.io.File;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Core 数据处理、校验、树形与工具原语端到端集成测试
 *
 * @author Ateng
 * @since 2026-10-19
 */
@SpringBootTest
class CoreDataProcessingScenarioServiceTest {

    @Autowired
    private CoreDataProcessingScenarioService dataProcessingService;

    @Test
    @DisplayName("测试JsonUtil对象序列化与反序列化还原")
    void testProcessJson() {
        SampleUserDTO user = SampleUserDTO.builder()
                .id(1L)
                .name("李四")
                .phone("13900001111")
                .status(0)
                .createTime(LocalDateTime.of(2026, 10, 19, 10, 30, 0))
                .build();

        Pair<String, SampleUserDTO> pair = dataProcessingService.processJson(user);
        assertThat(pair.left()).contains("\"id\":1");
        assertThat(pair.right().getId()).isEqualTo(1L);
    }

    @Test
    @DisplayName("测试CollectionUtil安全切分分批")
    void testPartitionList() {
        List<Integer> list = List.of(1, 2, 3, 4, 5, 6, 7);
        List<List<Integer>> batches = dataProcessingService.partitionList(list, 3);

        assertThat(batches).hasSize(3);
        assertThat(batches.get(0)).containsExactly(1, 2, 3);
        assertThat(batches.get(1)).containsExactly(4, 5, 6);
        assertThat(batches.get(2)).containsExactly(7);
    }

    @Test
    @DisplayName("测试MoreCollectors.toSafeMap空值容忍安全收集")
    void testCollectToSafeMap() {
        List<SampleUserDTO> users = List.of(
                SampleUserDTO.builder().id(1L).email("user1@example.com").build(),
                SampleUserDTO.builder().id(2L).email(null).build() // Value 为 null
        );

        Map<Long, String> safeMap = dataProcessingService.collectToSafeMap(users);
        assertThat(safeMap).hasSize(2)
                .containsEntry(1L, "user1@example.com")
                .containsEntry(2L, null);
    }

    @Test
    @DisplayName("测试TreeUtil线性组装与递归剪枝过滤")
    void testAssembleAndFilterTree() {
        List<SampleMenuNodeDTO> flatList = new ArrayList<>();
        flatList.add(new SampleMenuNodeDTO(1L, 0L, "系统管理", "/sys", 1));
        flatList.add(new SampleMenuNodeDTO(2L, 1L, "用户管理", "/sys/user", 1));
        flatList.add(new SampleMenuNodeDTO(3L, 2L, "用户导出", "/sys/user/export", 1));
        flatList.add(new SampleMenuNodeDTO(4L, 1L, "角色管理", "/sys/role", 2));

        // 1. 组装完整树结构
        List<SampleMenuNodeDTO> fullTree = dataProcessingService.assembleTree(flatList);
        assertThat(fullTree).hasSize(1);
        assertThat(fullTree.get(0).getChildren()).hasSize(2);

        // 2. 剪枝过滤树：仅保留包含“导出”的链路（系统管理 -> 用户管理 -> 用户导出），角色管理被剪除
        List<SampleMenuNodeDTO> filteredTree = dataProcessingService.filterTree(fullTree, "导出");
        assertThat(filteredTree).hasSize(1);
        assertThat(filteredTree.get(0).getName()).isEqualTo("系统管理");
        assertThat(filteredTree.get(0).getChildren()).hasSize(1);
        assertThat(filteredTree.get(0).getChildren().get(0).getName()).isEqualTo("用户管理");
        assertThat(filteredTree.get(0).getChildren().get(0).getChildren().get(0).getName()).isEqualTo("用户导出");
    }

    @Test
    @DisplayName("测试ValidatorUtil高频正则与对象校验")
    void testValidatorUtil() {
        assertThat(ValidatorUtil.isMobile("13812345678")).isTrue();
        assertThat(ValidatorUtil.isIdCard("11010519491231002X")).isTrue();
        assertThat(ValidatorUtil.isEmail("admin@atengk.org")).isTrue();
        assertThat(ValidatorUtil.isIpv4("192.168.1.1")).isTrue();
        assertThat(ValidatorUtil.isCarLicense("京A88888")).isTrue();

        // 合法对象校验
        SampleUserDTO validUser = SampleUserDTO.builder()
                .id(1L)
                .name("王五")
                .phone("13800000000")
                .status(1)
                .build();
        dataProcessingService.validateUser(validUser);

        // 非法对象快速失败校验并抛出 BusinessException
        SampleUserDTO invalidUser = SampleUserDTO.builder()
                .name("")
                .phone("13800000000")
                .build();
        assertThatThrownBy(() -> dataProcessingService.validateUser(invalidUser))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("姓名不能为空");

        // 分组校验：Update 分组要求 id 必填
        SampleUserDTO missingIdUser = SampleUserDTO.builder()
                .name("张三")
                .phone("13800000000")
                .status(1)
                .build();
        assertThatThrownBy(() -> dataProcessingService.validateUser(missingIdUser, ValidationGroups.Update.class))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("修改时用户ID不能为空");
    }

    @Test
    @DisplayName("测试ValidList批量包装级联校验")
    void testValidList() {
        ValidList<SampleUserDTO> validList = new ValidList<>();
        validList.add(SampleUserDTO.builder().id(1L).name("测试1").phone("13800001111").status(0).build());
        dataProcessingService.validateBatchUsers(validList);

        // 包含非法元素触发级联校验失败
        ValidList<SampleUserDTO> invalidList = new ValidList<>();
        invalidList.add(SampleUserDTO.builder().id(2L).name("").phone("13800002222").status(0).build());
        assertThatThrownBy(() -> dataProcessingService.validateBatchUsers(invalidList))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("姓名不能为空");
    }

    @Test
    @DisplayName("测试BeanUtil对象属性拷贝")
    void testCopyUser() {
        SampleUserDTO source = SampleUserDTO.builder().id(999L).name("拷贝测试").build();
        SampleUserDTO target = dataProcessingService.copyUser(source);

        assertThat(target).isNotNull();
        assertThat(target.getId()).isEqualTo(999L);
        assertThat(target.getName()).isEqualTo("拷贝测试");
    }

    @Test
    @DisplayName("测试LambdaUtil方法引用解析推导属性名")
    void testResolveFieldName() {
        String fieldName = dataProcessingService.resolveFieldName();
        assertThat(fieldName).isEqualTo("name");
    }

    @Test
    @DisplayName("测试FileUtil路径穿越防御与容量格式化")
    void testProcessFileMetadata() {
        Triple<String, String, String> triple = dataProcessingService.processFileMetadata(
                "../../etc/secret.PDF",
                10485760L
        );
        // 防御 ../ 路径穿越
        assertThat(triple.left()).isEqualTo("secret.PDF");
        // 扩展名小写
        assertThat(triple.middle()).isEqualTo("pdf");
        // 格式化容量
        assertThat(triple.right()).isEqualTo("10.00 MB");
    }

    @Test
    @DisplayName("测试DateTimeUtil与RandomUtil")
    void testProcessDateTimeAndRandom() {
        LocalDateTime ldt = LocalDateTime.of(2026, 10, 19, 14, 30, 0);
        Pair<String, String> pair = dataProcessingService.processDateTimeAndRandom(ldt);

        assertThat(pair.left()).isEqualTo("2026-10-19 14:30:00");
        assertThat(pair.right()).hasSize(6).containsOnlyDigits();
    }

    @Test
    @DisplayName("测试TreeUtil展平、路径探寻与叶子节点提取")
    void testAdvancedTreeOperations() {
        List<SampleMenuNodeDTO> flatList = new ArrayList<>();
        flatList.add(new SampleMenuNodeDTO(1L, 0L, "系统管理", "/sys", 1));
        flatList.add(new SampleMenuNodeDTO(2L, 1L, "用户管理", "/sys/user", 1));
        flatList.add(new SampleMenuNodeDTO(3L, 2L, "用户导出", "/sys/user/export", 1));
        flatList.add(new SampleMenuNodeDTO(4L, 1L, "角色管理", "/sys/role", 2));

        List<SampleMenuNodeDTO> fullTree = dataProcessingService.assembleTree(flatList);
        Triple<List<SampleMenuNodeDTO>, List<SampleMenuNodeDTO>, List<SampleMenuNodeDTO>> result =
                dataProcessingService.demonstrateAdvancedTreeOperations(fullTree);

        // 扁平展平包含 4 个节点
        assertThat(result.left()).hasSize(4);

        // 面包屑链路为 系统管理 -> 用户管理 -> 用户导出
        List<SampleMenuNodeDTO> breadcrumbs = result.middle();
        assertThat(breadcrumbs).hasSize(3);
        assertThat(breadcrumbs.get(0).getName()).isEqualTo("系统管理");
        assertThat(breadcrumbs.get(1).getName()).isEqualTo("用户管理");
        assertThat(breadcrumbs.get(2).getName()).isEqualTo("用户导出");

        // 叶子节点为 用户导出 与 角色管理
        List<SampleMenuNodeDTO> leaves = result.right();
        assertThat(leaves).hasSize(2);
        assertThat(leaves.stream().map(SampleMenuNodeDTO::getName)).containsExactlyInAnyOrder("用户导出", "角色管理");
    }

    @Test
    @DisplayName("测试FileUtil安全读取与受管临时文件")
    void testFileAndStreamSafety() {
        byte[] payload = "SecurePayloadData".getBytes();
        Pair<byte[], String> pair = dataProcessingService.demonstrateFileAndStreamSafety(payload);

        assertThat(pair.left()).isEqualTo(payload);
        File tempFile = new File(pair.right());
        try {
            assertThat(tempFile).exists();
        } finally {
            // 测试结束后确定性物理删除，杜绝磁盘残留
            if (tempFile.exists()) {
                tempFile.delete();
            }
        }
    }

    @Test
    @DisplayName("测试DateTimeUtil工作日顺延与跨度推演")
    void testWorkdays() {
        // 2026-10-23 是周五
        LocalDate friday = LocalDate.of(2026, 10, 23);
        Triple<Boolean, LocalDate, Long> triple = dataProcessingService.demonstrateWorkdays(friday);

        assertThat(triple.left()).isTrue();
        // 周五顺延 3 个工作日：周一(26)、周二(27)、周三(28)
        assertThat(triple.middle()).isEqualTo(LocalDate.of(2026, 10, 28));
        assertThat(triple.right()).isEqualTo(3L);
    }

    @Test
    @DisplayName("测试JsonUtil校验探针与树流读取")
    void testJsonAdvanced() {
        String json = "{\"service\":\"ateng-core\",\"active\":true}";
        Pair<Boolean, JsonNode> pair = dataProcessingService.demonstrateJsonAdvanced(json);

        assertThat(pair.left()).isTrue();
        assertThat(pair.right().get("service").asText()).isEqualTo("ateng-core");
        assertThat(pair.right().get("active").asBoolean()).isTrue();
    }

    @Test
    @DisplayName("测试MoreCollectors.distinctByKey流式按属性去重")
    void testStreamDistinct() {
        List<SampleUserDTO> users = List.of(
                SampleUserDTO.builder().id(1L).name("张三").build(),
                SampleUserDTO.builder().id(2L).name("李四").build(),
                SampleUserDTO.builder().id(3L).name("张三").build() // 重复名称
        );

        List<SampleUserDTO> distinct = dataProcessingService.demonstrateStreamDistinct(users);
        assertThat(distinct).hasSize(2);
        assertThat(distinct.stream().map(SampleUserDTO::getName)).containsExactly("张三", "李四");
    }

    @Test
    @DisplayName("测试通用新原语(StringUtil, StopWatch, NetUtil, ConvertUtil, ExceptionUtil, SpringUtil)")
    void testGeneralPrimitives() {
        Map<String, Object> map = dataProcessingService.demonstrateGeneralPrimitives();

        assertThat(map.get("camelCase")).isEqualTo("userProfileName");
        assertThat(map.get("underlineCase")).isEqualTo("user_profile_name");
        assertThat(map.get("formattedText")).isEqualTo("User Admin role is SuperAdmin");
        assertThat(map.get("truncatedText")).isEqualTo("VeryLongBu");
        assertThat((Long) map.get("stopWatchTotalMillis")).isNotNegative();
        assertThat(map.get("localIp")).isNotNull();
        assertThat(map.get("convertedLong")).isEqualTo(9876543210L);
        assertThat(map.get("rootCauseMessage")).isEqualTo("真实根因");
        assertThat(map.get("hasServiceBean")).isEqualTo(true);
    }

    @Test
    @DisplayName("测试ConcurrentLruCache纯原生有界LRU缓存淘汰与原子计算")
    void testDemonstrateConcurrentLruCache() {
        Map<String, Object> results = dataProcessingService.demonstrateConcurrentLruCache();

        assertThat(results.get("initialSize")).isEqualTo(2);
        assertThat(results.get("k1Value")).isEqualTo("v1");
        assertThat(results.get("afterEvictSize")).isEqualTo(2);
        assertThat(results.get("hasK1")).isEqualTo(true);
        assertThat(results.get("hasK2")).isEqualTo(false); // 淘汰
        assertThat(results.get("hasK3")).isEqualTo(true);
        assertThat(results.get("computedValue")).isEqualTo("computed_k4");
    }

    @Test
    @DisplayName("测试SpelUtil安全受限求值与方法参数动态别名绑定")
    void testDemonstrateSpelEvaluation() {
        SampleUserDTO user = SampleUserDTO.builder().id(999L).name("诸葛孔明").build();
        Map<String, Object> results = dataProcessingService.demonstrateSpelEvaluation(10086L, "ANDROID", user);

        assertThat(results.get("mathResult")).isEqualTo(250);
        assertThat(results.get("userName")).isEqualTo("诸葛孔明");
        assertThat(results.get("varResult")).isEqualTo("ORD-9999");
        assertThat(results.get("paramBinding")).isEqualTo("10086:ANDROID:诸葛孔明");
        assertThat(results.get("positionalBinding")).isEqualTo("10086-ANDROID");
        assertThat(results.get("injectionBlocked")).isEqualTo(true);
    }
}
