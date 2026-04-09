package io.github.skyleew.relationmapping.service;

import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.skyleew.relationmapping.domain.RelationModelStructure;
import io.github.skyleew.relationmapping.domain.SubSqlResult;
import io.github.skyleew.relationmapping.domain.TableStructure;
import io.github.skyleew.relationmapping.utils.ReflectionFieldUtils;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Queue;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 校验 hasWhere 子查询合并时的参数占位符重排逻辑，避免多个 EXISTS 合并后触发 MyBatis-Plus 语法异常。
 */
class HasRelationServiceTest {

    /**
     * 多个 hasWhere 合并后，占位符必须按最终参数列表重新编号，否则主 Wrapper.apply 会在运行时抛异常。
     */
    @Test
    @DisplayName("relationModelQuery 应重排多个 hasWhere 的 apply 占位符")
    void relationModelQueryShouldReindexApplyPlaceholders() {
        FakeRelationSqlService fakeRelationSqlService = new FakeRelationSqlService(
            buildSubSqlResult("EXISTS (SELECT 1 FROM post rel1 WHERE rel1.user_id = user.id AND rel1.status = {0} AND rel1.type = {1})", 1, 2),
            buildSubSqlResult("EXISTS (SELECT 1 FROM dept rel1 WHERE rel1.id = user.dept_id AND rel1.name LIKE {0})", "%运维%")
        );
        HasRelationService hasRelationService = new HasRelationService(fakeRelationSqlService);

        Map<String, RelationModelStructure> tableStructureMap = new LinkedHashMap<>();
        tableStructureMap.put("posts", new RelationModelStructure());
        tableStructureMap.put("dept", new RelationModelStructure());

        LinkedHashMap<String, Wrapper<?>> relationQuery = new LinkedHashMap<>();
        relationQuery.put("posts", new QueryWrapper<>());
        relationQuery.put("dept", new QueryWrapper<>());

        SubSqlResult merged = hasRelationService.relationModelQuery(Object.class, tableStructureMap, relationQuery);

        assertEquals(
            "(EXISTS (SELECT 1 FROM post rel1 WHERE rel1.user_id = user.id AND rel1.status = {0} AND rel1.type = {1})) AND "
                + "(EXISTS (SELECT 1 FROM dept rel1 WHERE rel1.id = user.dept_id AND rel1.name LIKE {2}))",
            merged.getSql()
        );
        assertEquals(List.of(1, 2, "%运维%"), merged.getParameters());

        QueryWrapper<Object> mainWrapper = new QueryWrapper<>();
        mainWrapper.apply(merged.getSql(), merged.getParameters().toArray());
        String customSqlSegment = mainWrapper.getCustomSqlSegment();

        assertTrue(customSqlSegment.contains("MPGENVAL1"));
        assertTrue(customSqlSegment.contains("MPGENVAL2"));
        assertTrue(customSqlSegment.contains("MPGENVAL3"));
    }

    /**
     * 主表关联键在父类、关联字段声明为 Set 时，仍应正确收集 id 并回填完整集合。
     */
    @Test
    @DisplayName("relationModel 应支持父类字段和 Collection 回填")
    void relationModelShouldSupportInheritedFieldsAndCollectionField() {
        List<Object> relationRows = new ArrayList<>();
        relationRows.add(new LinkedHashMap<>(Map.of("id", 10L, "parentCode", "P001", "name", "子项A")));
        relationRows.add(new LinkedHashMap<>(Map.of("id", 11L, "parentCode", "P001", "name", "子项B")));

        LoadingRelationSqlService fakeRelationSqlService = new LoadingRelationSqlService(relationRows);
        HasRelationService hasRelationService = new HasRelationService(fakeRelationSqlService);

        RelationModelStructure relationModelStructure = buildRelationStructure();
        ParentEntity parentEntity = new ParentEntity();
        parentEntity.id = "P001";

        Map<String, RelationModelStructure> relationMap = new LinkedHashMap<>();
        relationMap.put("children", relationModelStructure);

        hasRelationService.relationModel(ParentEntity.class, relationMap, List.of(parentEntity), 1);

        assertEquals(List.of("P001"), fakeRelationSqlService.capturedRelationValues);
        assertEquals(2, parentEntity.children.size());
        assertTrue(parentEntity.children instanceof Set);
    }

    /**
     * 快速构造子查询片段，便于精确验证参数顺序和 SQL 占位符重排结果。
     *
     * @param sql 子查询 SQL 片段
     * @param parameters 与占位符顺序一致的参数列表
     * @return 组装完成的结果对象
     */
    private static SubSqlResult buildSubSqlResult(String sql, Object... parameters) {
        SubSqlResult subSqlResult = new SubSqlResult();
        subSqlResult.setSql(sql);
        subSqlResult.getParameters().addAll(List.of(parameters));
        return subSqlResult;
    }

    /**
     * 用固定返回值替代真实 SQL 生成逻辑，让测试只聚焦占位符合并规则本身。
     */
    private static final class FakeRelationSqlService extends RelationSqlService {

        /**
         * 预设返回结果队列，按调用顺序依次弹出，模拟多个 hasWhere 子查询。
         */
        private final Queue<SubSqlResult> results = new ArrayDeque<>();

        /**
         * 初始化固定返回的子查询结果，避免测试依赖数据库或 MyBatis 元数据。
         *
         * @param subSqlResults 预设的子查询结果集合
         */
        private FakeRelationSqlService(SubSqlResult... subSqlResults) {
            super(new ObjectMapper(), null);
            this.results.addAll(List.of(subSqlResults));
        }

        /**
         * 按预设顺序返回子查询结果，替代真实的 SQL 组装逻辑。
         *
         * @param fieldMetadata 当前关联元数据
         * @param queryWrapper 当前关联条件
         * @return 预设好的子查询片段
         */
        @Override
        public SubSqlResult getSubSelectResult(RelationModelStructure fieldMetadata, Wrapper<?> queryWrapper) {
            return results.remove();
        }
    }

    /**
     * 用固定关联结果替代真实 SQL 执行，便于验证 HasRelationService 的字段读取与集合回填逻辑。
     */
    private static final class LoadingRelationSqlService extends RelationSqlService {

        /**
         * 固定返回的关联记录集合。
         */
        private final List<Object> relationRows;

        /**
         * 记录 HasRelationService 传入的关联值，便于断言父类字段读取是否正确。
         */
        private List<Object> capturedRelationValues;

        /**
         * 初始化固定关联结果。
         *
         * @param relationRows 固定关联结果
         */
        private LoadingRelationSqlService(List<Object> relationRows) {
            super(new ObjectMapper(), null);
            this.relationRows = relationRows;
        }

        /**
         * 返回固定关联结果，并记录本次查询使用的关联键列表。
         *
         * @param fieldMetadata 当前关联元数据
         * @return 固定关联结果
         */
        @Override
        public List<Object> getResult(RelationModelStructure fieldMetadata) {
            capturedRelationValues = new ArrayList<>(fieldMetadata.getSelfTableStructure().getRelationValues());
            return relationRows;
        }
    }

    /**
     * 构造最小关系元数据，覆盖父类字段读取与 Set 集合回填路径。
     *
     * @return 最小关系元数据
     */
    private RelationModelStructure buildRelationStructure() {
        TableStructure selfTableStructure = new TableStructure();
        selfTableStructure.setReflectClass(ParentEntity.class);
        selfTableStructure.setRelationField(ReflectionFieldUtils.findField(ParentEntity.class, "id"));
        selfTableStructure.setAnnotationField(ReflectionFieldUtils.findField(ParentEntity.class, "children"));
        selfTableStructure.setRelationFieldKey("id");

        TableStructure relationTableStructure = new TableStructure();
        relationTableStructure.setReflectClass(ChildEntity.class);
        relationTableStructure.setRelationField(ReflectionFieldUtils.findField(ChildEntity.class, "parentCode"));
        relationTableStructure.setRelationFieldKey("parent_code");

        RelationModelStructure relationModelStructure = new RelationModelStructure();
        relationModelStructure.setSelfTableStructure(selfTableStructure);
        relationModelStructure.setRelationTableStructure(relationTableStructure);
        return relationModelStructure;
    }

    /**
     * 模拟主表父类，把关联键字段放在继承层级中。
     */
    private static class ParentBaseEntity {

        /**
         * 父类主键字段用于验证继承树字段读取。
         */
        public String id;
    }

    /**
     * 模拟主表实体，关联字段声明为 Set 以验证 Collection 语义回填。
     */
    private static final class ParentEntity extends ParentBaseEntity {

        /**
         * 关联集合字段用于验证非 List 集合也能正确回填。
         */
        public Set<ChildEntity> children;
    }

    /**
     * 模拟子表父类，把外键字段放在继承层级中。
     */
    private static class ChildBaseEntity {

        /**
         * 子表关联字段定义在父类，用于验证继承树字段读取。
         */
        public String parentCode;
    }

    /**
     * 模拟子表实体，作为关联结果的目标类型。
     */
    private static final class ChildEntity extends ChildBaseEntity {

        /**
         * 主键字段用于形成稳定对象结构。
         */
        public Long id;

        /**
         * 普通业务字段用于验证关联结果转换是否成功。
         */
        public String name;
    }
}
