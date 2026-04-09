package io.github.skyleew.relationmapping.utils;

import io.github.skyleew.relationmapping.annotation.RelationModel;
import io.github.skyleew.relationmapping.domain.TableStructure;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

/**
 * 校验关系元数据构建依赖的字段扫描逻辑，避免父类字段和 Collection 泛型再次失效。
 */
class RelationUtilTest {

    /**
     * Collection 关系字段应能正确解析出泛型实体类型，而不是把 Set 本身当作目标实体。
     */
    @Test
    @DisplayName("getTargetClass 应支持 Collection 泛型解析")
    void getTargetClassShouldResolveCollectionGeneric() throws Exception {
        Field relationField = ReflectionFieldUtils.findField(ParentEntity.class, "children");
        RelationModel relationModel = relationField.getAnnotation(RelationModel.class);
        TableStructure tableStructure = new TableStructure();

        Method method = RelationUtil.class.getDeclaredMethod("getTargetClass", Field.class, RelationModel.class, TableStructure.class);
        method.setAccessible(true);
        method.invoke(null, relationField, relationModel, tableStructure);

        assertEquals(ChildEntity.class, tableStructure.getReflectClass());
    }

    /**
     * 主表和子表的关联字段定义在父类时，字段搜索也应当能正确命中。
     */
    @Test
    @DisplayName("primaryKeyNameSearchFiled 应支持继承树字段")
    void primaryKeyNameSearchFiledShouldSupportInheritedFields() throws Exception {
        Method method = RelationUtil.class.getDeclaredMethod("primaryKeyNameSearchFiled", String.class, List.class);
        method.setAccessible(true);

        Field parentIdField = (Field) method.invoke(null, "id", ReflectionFieldUtils.getAllFields(ParentEntity.class));
        Field childParentCodeField = (Field) method.invoke(null, "parent_code", ReflectionFieldUtils.getAllFields(ChildEntity.class));

        assertNotNull(parentIdField);
        assertNotNull(childParentCodeField);
        assertEquals("id", parentIdField.getName());
        assertEquals("parentCode", childParentCodeField.getName());
    }

    /**
     * 模拟主表父类，把主键字段放在继承层级中。
     */
    private static class ParentBaseEntity {

        /**
         * 父类主键字段用于验证继承树字段搜索。
         */
        public String id;
    }

    /**
     * 模拟主表实体，关联字段声明为 Set 以验证 Collection 泛型解析。
     */
    private static class ParentEntity extends ParentBaseEntity {

        /**
         * Collection 关系字段用于验证 Set 泛型目标实体解析。
         */
        @RelationModel(field = "id", targetField = "parent_code")
        public Set<ChildEntity> children;
    }

    /**
     * 模拟子表父类，把外键字段放在继承层级中。
     */
    private static class ChildBaseEntity {

        /**
         * 父类外键字段用于验证继承树字段搜索。
         */
        public String parentCode;
    }

    /**
     * 模拟子表实体，作为 Collection 关系字段的泛型目标类型。
     */
    private static class ChildEntity extends ChildBaseEntity {

        /**
         * 普通业务字段用于形成完整对象结构。
         */
        public Long id;
    }
}
