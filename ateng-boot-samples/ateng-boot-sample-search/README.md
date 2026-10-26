# ateng-boot-sample-search 场景样例工程

本工程为 `ateng-boot-search` 全文检索中间件 Starter 的 1:1 独立场景自验工程，以电商商品搜索（`ProductDoc`）全真场景贯通：
- 单文档 CRUD、局部更新（Partial Update）与 OCC 乐观并发控制；
- 严格模式与宽容模式双模批量写入；
- `SearchBulkProcessor` 异步缓冲流式写入与安全排空；
- 关键字检索与 `@HighlightField` 多字段高亮富文本自动回填；
- 复合布尔筛选、价格区间过滤与品牌聚合度量；
- Geo 空间距离筛选与周边商品排序；
- KNN AI 向量近似度检索；
- 浅分页与 Search-After 游标滚动分页及防 OOM 刚性熔断；
- SpEL 动态时序分表与别名原子热切换；
- Actuator 健康探针与 Micrometer 性能度量监控。

---

## 运行与离线验证

工程内嵌 100% 内存自洽的 MockWebServer，无需依赖外部物理 Elasticsearch 集群或 Docker 容器：

```bash
mvn test -pl ateng-boot-samples/ateng-boot-sample-search -am -T 1C -nsu -B
```
