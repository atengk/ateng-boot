# 依赖管理采用 BOM Import 模式而非继承 parent

外部业务工程若必须继承框架的 parent POM 会剥夺其自主配置构建插件、定制多继承与指定根属性的灵活性。我们决定根工程与对外交付的 `ateng-boot-bom` 统一采用 `<dependencyManagement>` 的 `<scope>import</scope>` 模式分发依赖版本，赋予下游工程最大的依赖组合自由度。
