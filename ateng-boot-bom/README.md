# ateng-boot-bom 依赖版本管控中心

> 统一依赖版本物料清单（BOM），为按需引入与微服务架构提供无侵入版本管控。

---

## 1. 模块定位与 Maven 坐标

`ateng-boot-bom` 是纯 `<dependencyManagement>` 物料清单。业务工程通过 `<scope>import</scope>` 引入后，声明 `io.github.atengk` 旗下任何子模块均无需指定 `<version>` 标签，彻底避免各子模块版本割裂冲突。

```xml
<dependencyManagement>
    <dependencies>
        <dependency>
            <groupId>io.github.atengk</groupId>
            <artifactId>ateng-boot-bom</artifactId>
            <version>0.0.5</version>
            <type>pom</type>
            <scope>import</scope>
        </dependency>
    </dependencies>
</dependencyManagement>
```

---

## 2. 下游按需引入示例 (子模块免配版本)

```xml
<dependencies>
    <!-- 仅引入通用核心底座 -->
    <dependency>
        <groupId>io.github.atengk</groupId>
        <artifactId>ateng-boot-core</artifactId>
    </dependency>

    <!-- 仅引入 Web 运行时增强 -->
    <dependency>
        <groupId>io.github.atengk</groupId>
        <artifactId>ateng-boot-web</artifactId>
    </dependency>

    <!-- 仅引入持久层增强 -->
    <dependency>
        <groupId>io.github.atengk</groupId>
        <artifactId>ateng-boot-mybatis-plus</artifactId>
    </dependency>
</dependencies>
```
