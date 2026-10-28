package io.github.atengk.boot.mybatisplus.dynamicsql;

import io.github.atengk.boot.mybatisplus.properties.DynamicSqlProperties;
import org.apache.ibatis.mapping.SqlCommandType;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;
import org.springframework.core.io.support.ResourcePatternResolver;
import org.springframework.util.ClassUtils;
import org.springframework.util.StringUtils;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;

import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.transform.OutputKeys;
import javax.xml.transform.Transformer;
import javax.xml.transform.TransformerFactory;
import javax.xml.transform.dom.DOMSource;
import javax.xml.transform.stream.StreamResult;
import java.io.InputStream;
import java.io.StringWriter;
import java.util.*;

/**
 * 基于类路径与文件系统资源的外部动态 SQL 加载器
 * 扫描并解析包含 <dynamic-sql> 或 <mapper> 的外部 XML 资源片段
 *
 * @author Ateng
 * @since 2026-10-18
 */
public class ResourceExternalSqlLoader implements ExternalSqlLoader {

    private static final Logger log = LoggerFactory.getLogger(ResourceExternalSqlLoader.class);

    private final DynamicSqlProperties properties;
    private final ResourcePatternResolver resourceResolver;

    public ResourceExternalSqlLoader(DynamicSqlProperties properties) {
        this.properties = Objects.requireNonNull(properties, "DynamicSqlProperties must not be null");
        this.resourceResolver = new PathMatchingResourcePatternResolver();
    }

    public ResourceExternalSqlLoader(DynamicSqlProperties properties, ResourcePatternResolver resourceResolver) {
        this.properties = Objects.requireNonNull(properties, "DynamicSqlProperties must not be null");
        this.resourceResolver = Objects.requireNonNull(resourceResolver, "ResourcePatternResolver must not be null");
    }

    @Override
    public List<DynamicSqlDefinition> loadAll() {
        List<String> locations = properties.getLocations();
        if (locations == null || locations.isEmpty()) {
            return Collections.emptyList();
        }

        Map<String, DynamicSqlDefinition> definitionMap = new LinkedHashMap<>();
        for (String location : locations) {
            if (!StringUtils.hasText(location)) {
                continue;
            }
            try {
                Resource[] resources = resourceResolver.getResources(location.trim());
                if (resources == null || resources.length == 0) {
                    continue;
                }
                for (Resource resource : resources) {
                    if (resource != null && resource.exists() && resource.isReadable()) {
                        parseResource(resource, definitionMap);
                    }
                }
            } catch (Exception e) {
                log.warn("扫描外部动态 SQL 资源路径失败，跳过该路径: [{}], 原因: {}", location, e.getMessage());
            }
        }
        return new ArrayList<>(definitionMap.values());
    }

    /**
     * 解析单个 XML 资源文件中的动态 SQL 语句
     */
    private void parseResource(Resource resource, Map<String, DynamicSqlDefinition> definitionMap) {
        try (InputStream inputStream = resource.getInputStream()) {
            DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
            // 防范 XXE (XML 外部实体注入) 安全风险
            try {
                factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
                factory.setFeature("http://xml.org/sax/features/external-general-entities", false);
                factory.setFeature("http://xml.org/sax/features/external-parameter-entities", false);
                factory.setXIncludeAware(false);
                factory.setExpandEntityReferences(false);
            } catch (Exception ex) {
                log.debug("XML 解析器特性设置跳过: {}", ex.getMessage());
            }
            factory.setNamespaceAware(false);

            DocumentBuilder builder = factory.newDocumentBuilder();
            Document document = builder.parse(inputStream);
            Element root = document.getDocumentElement();

            TransformerFactory transformerFactory = TransformerFactory.newInstance();
            try {
                transformerFactory.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
            } catch (Exception ignored) {
            }
            Transformer transformer = transformerFactory.newTransformer();
            transformer.setOutputProperty(OutputKeys.OMIT_XML_DECLARATION, "yes");

            NodeList childNodes = root.getChildNodes();
            for (int i = 0; i < childNodes.getLength(); i++) {
                Node node = childNodes.item(i);
                if (node.getNodeType() != Node.ELEMENT_NODE) {
                    continue;
                }
                Element element = (Element) node;
                String tagName = element.getTagName().toLowerCase();
                if (isStatementTag(tagName)) {
                    DynamicSqlDefinition def = parseStatementElement(element, tagName, transformer);
                    if (def != null) {
                        definitionMap.put(def.getId(), def);
                    }
                }
            }
        } catch (Exception e) {
            log.error("解析外部动态 SQL 资源文件失败: [{}], 原因: {}", resource.getDescription(), e.getMessage(), e);
        }
    }

    private boolean isStatementTag(String tagName) {
        return "statement".equals(tagName) ||
                "select".equals(tagName) ||
                "insert".equals(tagName) ||
                "update".equals(tagName) ||
                "delete".equals(tagName);
    }

    /**
     * 将 statement/select/insert/update/delete 节点解析为 DynamicSqlDefinition
     */
    private DynamicSqlDefinition parseStatementElement(Element element, String tagName, Transformer transformer) {
        String id = element.getAttribute("id");
        if (!StringUtils.hasText(id)) {
            log.warn("动态 SQL 语句节点缺少 id 属性，已忽略: {}", element.getTagName());
            return null;
        }

        // 解析 SqlCommandType
        SqlCommandType commandType = resolveCommandType(element, tagName);

        // 解析 resultType
        Class<?> resultClass = resolveResultType(element);

        // 解析 description
        String description = element.getAttribute("description");

        // 提取内部完整 XML SQL 脚本文本
        String innerXml = extractInnerXml(element, transformer);
        if (!StringUtils.hasText(innerXml)) {
            log.warn("动态 SQL 语句 [{}] 的脚本文本为空，已忽略", id);
            return null;
        }

        return DynamicSqlDefinition.builder()
                .id(id.trim())
                .sqlScript(innerXml)
                .commandType(commandType)
                .resultType(resultClass)
                .description(description)
                .autoScriptWrap(properties.isAutoScriptWrap())
                .build();
    }

    private SqlCommandType resolveCommandType(Element element, String tagName) {
        return switch (tagName) {
            case "select" -> SqlCommandType.SELECT;
            case "insert" -> SqlCommandType.INSERT;
            case "update" -> SqlCommandType.UPDATE;
            case "delete" -> SqlCommandType.DELETE;
            default -> {
                String cmdAttr = element.getAttribute("commandType");
                if (StringUtils.hasText(cmdAttr)) {
                    try {
                        yield SqlCommandType.valueOf(cmdAttr.trim().toUpperCase());
                    } catch (IllegalArgumentException e) {
                        yield SqlCommandType.SELECT;
                    }
                }
                yield SqlCommandType.SELECT;
            }
        };
    }

    private Class<?> resolveResultType(Element element) {
        String resultTypeAttr = element.getAttribute("resultType");
        if (StringUtils.hasText(resultTypeAttr)) {
            try {
                return ClassUtils.forName(resultTypeAttr.trim(), ClassUtils.getDefaultClassLoader());
            } catch (Exception e) {
                log.warn("无法加载动态 SQL 指定的 resultType [{}]，将回退为 Map.class", resultTypeAttr);
            }
        }
        return Map.class;
    }

    private String extractInnerXml(Element element, Transformer transformer) {
        NodeList children = element.getChildNodes();
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < children.getLength(); i++) {
            Node child = children.item(i);
            short nodeType = child.getNodeType();
            if (nodeType == Node.TEXT_NODE || nodeType == Node.CDATA_SECTION_NODE) {
                sb.append(child.getNodeValue());
            } else if (nodeType == Node.ELEMENT_NODE) {
                try {
                    StringWriter writer = new StringWriter();
                    transformer.transform(new DOMSource(child), new StreamResult(writer));
                    sb.append(writer.toString());
                } catch (Exception e) {
                    sb.append(child.getTextContent());
                }
            }
        }
        return sb.toString().trim();
    }
}
