package io.github.atengk.boot.sample.excel.fixture;

import io.github.atengk.boot.sample.excel.enums.SampleGenderEnum;
import io.github.atengk.boot.sample.excel.vo.DepartmentUserMergeVO;
import io.github.atengk.boot.sample.excel.vo.SecurityUserExcelVO;
import io.github.atengk.boot.sample.excel.vo.UserExcelVO;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Excel 示例工程通用 Mock 数据夹具
 * <p>
 * 提供标准自洽的 Mock 数据集合，消除跨场景服务与测试类的重复构造与依恋情结。
 * </p>
 *
 * @author Ateng
 * @since 2026-10-28
 */
public final class SampleDataFixture {

    private SampleDataFixture() {
        // 工具类禁止实例化
    }

    /**
     * 创建标准示例用户数据列表（4 条）
     *
     * @return 用户视图对象列表
     */
    public static List<UserExcelVO> createMockUserList() {
        List<UserExcelVO> list = new ArrayList<>();
        list.add(new UserExcelVO(1L, "张三", "13800138000", "zhangsan@example.com", SampleGenderEnum.MALE, "研发部", "0"));
        list.add(new UserExcelVO(2L, "李四", "13900139000", "lisi@example.com", SampleGenderEnum.FEMALE, "研发部", "0"));
        list.add(new UserExcelVO(3L, "王五", "13700137000", "wangwu@example.com", SampleGenderEnum.MALE, "市场部", "1"));
        list.add(new UserExcelVO(4L, "赵六", "13600136000", "zhaoliu@example.com", SampleGenderEnum.FEMALE, "财务部", "0"));
        return Collections.unmodifiableList(list);
    }

    /**
     * 创建异构多工作表编排专用用户数据列表（3 条）
     *
     * @return 用户视图对象列表
     */
    public static List<UserExcelVO> createMultiSheetUserList() {
        List<UserExcelVO> list = new ArrayList<>();
        list.add(new UserExcelVO(1001L, "张三", "13800138000", "zhangsan@example.com", SampleGenderEnum.MALE, "研发部", "0"));
        list.add(new UserExcelVO(1002L, "李四", "13900139000", "lisi@example.com", SampleGenderEnum.FEMALE, "市场部", "1"));
        list.add(new UserExcelVO(1003L, "王五", "13700137000", "wangwu@example.com", SampleGenderEnum.UNKNOWN, "财务部", "0"));
        return Collections.unmodifiableList(list);
    }

    /**
     * 创建部门与用户多级纵向合并示例数据列表
     *
     * @return 部门用户合并视图对象列表
     */
    public static List<DepartmentUserMergeVO> createMockDepartmentMergeList() {
        List<DepartmentUserMergeVO> list = new ArrayList<>();
        list.add(new DepartmentUserMergeVO("研发部", "张三", "Java 架构师"));
        list.add(new DepartmentUserMergeVO("研发部", "李雷", "前端工程师"));
        list.add(new DepartmentUserMergeVO("市场部", "韩梅梅", "商务经理"));
        list.add(new DepartmentUserMergeVO("市场部", "王五", "运营专员"));
        list.add(new DepartmentUserMergeVO("财务部", "赵六", "总账会计"));
        return Collections.unmodifiableList(list);
    }

    /**
     * 创建包含公式注入风险值与白名单公式的安全测试数据列表
     *
     * @return 安全视图对象列表
     */
    public static List<SecurityUserExcelVO> createMockSecurityUserList() {
        List<SecurityUserExcelVO> list = new ArrayList<>();
        list.add(new SecurityUserExcelVO(1001L, "=cmd|'/C calc'!A0", "=SUM(A1:B1)", "P7 专家"));
        list.add(new SecurityUserExcelVO(1002L, "+12345678", "=AVERAGE(C1:C10)", "P8 架构师"));
        list.add(new SecurityUserExcelVO(1003L, "普通员工", "普通文本", "P6 工程师"));
        return Collections.unmodifiableList(list);
    }
}
