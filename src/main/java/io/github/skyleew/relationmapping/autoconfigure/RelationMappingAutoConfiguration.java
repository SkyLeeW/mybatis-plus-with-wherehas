package io.github.skyleew.relationmapping.autoconfigure;

import io.github.skyleew.relationmapping.support.SpringContextHolder;
import io.github.skyleew.relationmapping.service.ExecuteWithSelectService;
import io.github.skyleew.relationmapping.service.HasRelationService;
import io.github.skyleew.relationmapping.service.RelationSqlService;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.context.annotation.Import;

/**
 * 自动注册关联查询所需 Bean，避免外部项目额外编写组件扫描配置。
 */
@AutoConfiguration
@Import({
    SpringContextHolder.class,
    ExecuteWithSelectService.class,
    HasRelationService.class,
    RelationSqlService.class
})
public class RelationMappingAutoConfiguration {
}

