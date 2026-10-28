package io.github.atengk.boot.web.dict;

import io.github.atengk.boot.core.code.CommonResultCode;
import io.github.atengk.boot.core.exception.BusinessException;
import io.github.atengk.boot.core.model.OptionItem;
import io.github.atengk.boot.core.model.Result;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/**
 * 枚举字典只读 HTTP 控制器
 * 统一显式遵循 Result 强类型契约，对外暴露单个枚举查询、批量合并及全量目录检索端点
 *
 * @author Ateng
 * @since 2026-10-28
 */
@RestController
@RequestMapping("${ateng.boot.web.enum.path-prefix:/api/system/enums}")
public class EnumDictionaryController {

    private final EnumDictionaryRegistry registry;

    public EnumDictionaryController(EnumDictionaryRegistry registry) {
        this.registry = registry;
    }

    /**
     * 查询全量已开放的枚举字典元数据目录与选项映射
     *
     * @return 全量枚举字典映射
     */
    @GetMapping
    public Result<Map<String, List<OptionItem<Object>>>> getAll() {
        return Result.success(registry.getAllOptions());
    }

    /**
     * 批量查询多个枚举字典的选项映射（GET Query 传参）
     *
     * @param names 枚举别名列表
     * @return 匹配的枚举字典映射
     */
    @GetMapping("/batch")
    public Result<Map<String, List<OptionItem<Object>>>> getBatchGet(
            @RequestParam(value = "names", required = false) List<String> names) {
        return Result.success(registry.getBatchOptions(names));
    }

    /**
     * 根据字典别名查询单个枚举的选项列表
     *
     * @param name 枚举别名
     * @return 选项列表
     */
    @GetMapping("/{name}")
    public Result<List<OptionItem<Object>>> getByName(@PathVariable("name") String name) {
        List<OptionItem<Object>> options = registry.getOptions(name);
        if (options == null) {
            throw new BusinessException(CommonResultCode.NOT_FOUND.getCode(), String.format("未找到名称为 [%s] 的枚举字典", name));
        }
        return Result.success(options);
    }

    /**
     * 批量查询多个枚举字典的选项映射（POST Body 传参）
     *
     * @param names 枚举别名列表
     * @return 匹配的枚举字典映射
     */
    @PostMapping("/batch")
    public Result<Map<String, List<OptionItem<Object>>>> getBatchPost(
            @RequestBody(required = false) List<String> names) {
        return Result.success(registry.getBatchOptions(names));
    }
}
