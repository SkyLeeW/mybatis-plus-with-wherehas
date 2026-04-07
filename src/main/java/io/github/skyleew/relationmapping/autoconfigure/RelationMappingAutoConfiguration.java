package io.github.skyleew.relationmapping.autoconfigure;

import io.github.skyleew.relationmapping.mapper.RawSqlMapper;
import io.github.skyleew.relationmapping.support.SpringContextHolder;
import io.github.skyleew.relationmapping.service.ExecuteWithSelectService;
import io.github.skyleew.relationmapping.service.HasRelationService;
import io.github.skyleew.relationmapping.service.RelationSqlService;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.session.SqlSessionFactory;
import org.mybatis.spring.SqlSessionFactoryBean;
import org.mybatis.spring.annotation.MapperScan;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.context.annotation.Import;

/**
 * 自动注册关联查询所需 Bean、库内 Mapper 与 XML 装载器，避免业务工程额外声明配置。
 */
@AutoConfiguration(afterName = {
    "com.baomidou.mybatisplus.autoconfigure.MybatisPlusAutoConfiguration",
    "org.mybatis.spring.boot.autoconfigure.MybatisAutoConfiguration"
})
@ConditionalOnClass({SqlSessionFactory.class, SqlSessionFactoryBean.class, RawSqlMapper.class})
@ConditionalOnBean(SqlSessionFactory.class)
@MapperScan(basePackageClasses = RawSqlMapper.class, annotationClass = Mapper.class)
@Import({
    SpringContextHolder.class,
    ExecuteWithSelectService.class,
    HasRelationService.class,
    RelationSqlService.class,
    RelationMappingMapperXmlLoader.class
})
public class RelationMappingAutoConfiguration {
}
