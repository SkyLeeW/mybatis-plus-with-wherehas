package io.github.skyleew.relationmapping.autoconfigure;

import org.apache.ibatis.builder.xml.XMLMapperBuilder;
import org.apache.ibatis.session.Configuration;
import org.apache.ibatis.session.SqlSessionFactory;
import org.springframework.beans.factory.SmartInitializingSingleton;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.util.List;

/**
 * 在全部 SqlSessionFactory 创建完成后，把库内 Mapper XML 主动注册到 MyBatis 配置中。
 */
public class RelationMappingMapperXmlLoader implements SmartInitializingSingleton {

    /**
     * 库内 Mapper XML 的固定类路径，避免误扫业务工程自己的 XML 资源。
     */
    private static final String RAW_SQL_MAPPER_XML_LOCATION = "classpath*:mapper/RawSqlMapper.xml";

    /**
     * 资源解析器用于从依赖 Jar 与本地 classes 目录统一读取 XML 资源。
     */
    private static final PathMatchingResourcePatternResolver RESOURCE_RESOLVER =
        new PathMatchingResourcePatternResolver();

    /**
     * 当前应用上下文中全部 SqlSessionFactory，逐个补齐同一份 Mapper 定义。
     */
    private final List<SqlSessionFactory> sqlSessionFactoryList;

    public RelationMappingMapperXmlLoader(List<SqlSessionFactory> sqlSessionFactoryList) {
        this.sqlSessionFactoryList = sqlSessionFactoryList;
    }

    /**
     * 单例全部就绪后再解析 XML，确保不会与 MyBatis 主配置初始化时序冲突。
     */
    @Override
    public void afterSingletonsInstantiated() {
        Resource[] mapperResources = resolveMapperResources();
        for (SqlSessionFactory sqlSessionFactory : sqlSessionFactoryList) {
            registerMapperXml(sqlSessionFactory.getConfiguration(), mapperResources);
        }
    }

    /**
     * 定位库内 XML，如果资源缺失则直接失败，避免启动成功但运行时才暴露绑定语句缺失。
     */
    private Resource[] resolveMapperResources() {
        try {
            Resource[] mapperResources = RESOURCE_RESOLVER.getResources(RAW_SQL_MAPPER_XML_LOCATION);
            if (mapperResources.length == 0) {
                throw new IllegalStateException("未找到库内 Mapper XML 资源: " + RAW_SQL_MAPPER_XML_LOCATION);
            }
            return mapperResources;
        } catch (IOException exception) {
            throw new UncheckedIOException("读取库内 Mapper XML 资源失败", exception);
        }
    }

    /**
     * 仅向尚未加载过该 XML 的 Configuration 注册映射，避免重复解析触发重复语句异常。
     */
    private void registerMapperXml(Configuration configuration, Resource[] mapperResources) {
        for (Resource mapperResource : mapperResources) {
            String resourceKey = buildResourceKey(mapperResource);
            if (configuration.isResourceLoaded(resourceKey)) {
                continue;
            }
            parseMapperXml(configuration, mapperResource, resourceKey);
        }
    }

    /**
     * 使用与 XMLMapperBuilder 一致的资源标识，保证重复加载判断和 MyBatis 内部行为保持一致。
     */
    private String buildResourceKey(Resource mapperResource) {
        return mapperResource.toString();
    }

    /**
     * 将单个 XML 解析进目标 Configuration，使 RawSqlMapper 的语句定义可以被直接调用。
     */
    private void parseMapperXml(Configuration configuration, Resource mapperResource, String resourceKey) {
        try (InputStream inputStream = mapperResource.getInputStream()) {
            XMLMapperBuilder xmlMapperBuilder = new XMLMapperBuilder(
                inputStream,
                configuration,
                resourceKey,
                configuration.getSqlFragments()
            );
            xmlMapperBuilder.parse();
        } catch (IOException exception) {
            throw new UncheckedIOException("加载库内 Mapper XML 失败: " + resourceKey, exception);
        }
    }
}
