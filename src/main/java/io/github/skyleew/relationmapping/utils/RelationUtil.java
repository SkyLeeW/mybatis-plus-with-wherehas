package io.github.skyleew.relationmapping.utils;

import cn.hutool.core.util.StrUtil;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.core.metadata.TableInfo;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import io.github.skyleew.relationmapping.annotation.RelationModel;
import io.github.skyleew.relationmapping.domain.RelationModelStructure;
import io.github.skyleew.relationmapping.domain.TableStructure;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.ResolvableType;

import java.lang.annotation.Annotation;
import java.util.Collection;
import java.lang.reflect.Field;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class RelationUtil {
    private static final Logger log = LoggerFactory.getLogger(RelationUtil.class);

    public  static Map<String, RelationModelStructure> checkRelation(Class<?> selfClazz) {
        Map<String, RelationModelStructure> relationMetadataMap = new HashMap<>();
        TableInfo selfClazzTableInfo = TableInfoHelper.getTableInfo(selfClazz);


        for (Field field : ReflectionFieldUtils.getAllFields(selfClazz)) {
            if (field.isAnnotationPresent(RelationModel.class)){

                RelationModel relationAnnotation = field.getAnnotation(RelationModel.class);
                // **关键验证**：如果是 count 统计，必须通过 table 属性指定目标实体类
                if (relationAnnotation.count() && relationAnnotation.table() == void.class) {
                    log.warn("注解 @RelationModel 在字段 '{}'上是 count 查询，但未指定 'table' 属性，已跳过。", field.getName());
                    continue;
                }
                // 如果是 count 查询，但字段类型不是数字或基础类型，则跳过
                if (relationAnnotation.count() && !FieldTypeUtils.isPrimitive(field)){
                    log.warn("注解 @RelationModel 在字段 '{}'上是 count 查询，但字段类型不是数字或基础类型，已跳过。", field.getName());

                    continue;
                }

                // 如果不是 count 查询，且字段类型是基础类型，则跳过
                if (!relationAnnotation.count() && FieldTypeUtils.isPrimitive(field)){
                    log.warn("注解 @RelationModel 在字段 '{}'上不是统计查询，字段类型是 {},无法映射 ，已跳过。", field.getName(),field.getType());

                    continue;
                };


                RelationModelStructure relationModelStructure = new RelationModelStructure();

                TableStructure selfTableStructure = new TableStructure();
                TableStructure relationTableStructure = new TableStructure();
                //初始化数据
                selfTableStructure.setTableInfo(selfClazzTableInfo);
                selfTableStructure.setAnnotationField(field);
                selfTableStructure.setReflectClass(selfClazz);

                relationTableStructure.setAnnotationField(field);
                relationModelStructure.setCount(relationAnnotation.count());


                //得到目标表的Class
                // 调用 getTargetClass 并传入注解实例
                getTargetClass(field, relationAnnotation, relationTableStructure);

                // 如果无法获取目标表信息，则跳过
                if (relationTableStructure.getTableInfo() == null) {
                    log.warn("无法为字段 '{}' 确定目标表的元信息，已跳过。", field.getName());
                    continue;
                }
                //得到关联用主表的主键和目标表的字段
                getRelationFieldKey(selfTableStructure,relationTableStructure);

                //如果关联的主键找不到,退出
                if(relationTableStructure.getRelationFieldKey().isEmpty()){
                    continue;
                }

                relationModelStructure.setSelfTableStructure(selfTableStructure);
                relationModelStructure.setRelationTableStructure(relationTableStructure);
                relationMetadataMap.put(field.getName(),relationModelStructure);
            }

        }

            return relationMetadataMap;
    }

    /**
     * 得到目标实体的实体类，并且得到table信息
     * @param field
     * @param relationMetaData
     */
    private  static void getTargetClass(Field field,RelationModel annotation,TableStructure relationMetaData) {


        Class<?> targetClass = annotation.table();

        // **主要逻辑**：如果注解中没有设置 table，则回退到从字段类型推断（用于非 count 的普通关联）
        if (targetClass == void.class) {
            targetClass = field.getType();
            if (Collection.class.isAssignableFrom(targetClass)) {
                ResolvableType genericType = ResolvableType.forField(field).getGeneric(0);
                targetClass = genericType.resolve();
            }
        }

        // 如果最终 targetClass 无效，则直接返回
        if (targetClass == null || targetClass == void.class) {
            return;
        }

        relationMetaData.setReflectClass(targetClass);
        TableInfo tinfo = TableInfoHelper.getTableInfo(targetClass);
        relationMetaData.setTableInfo(tinfo);

    }

    /**
     * 得到主表的关联字段属性
     * @param self 主标的模型文件
     * @param relationMetaData 关联表的模型文件
     */
    private static void getRelationFieldKey(TableStructure self ,  TableStructure relationMetaData) {

        String selfPk = "";
        String relationPk = "";
          for (Annotation annotation : self.getAnnotationField().getAnnotations()) {
              if (annotation.annotationType().equals(RelationModel.class)){
                  RelationModel oneToMany = (RelationModel) annotation;
                  selfPk = oneToMany.field();
                  relationPk = oneToMany.targetField();
              }
          }

          if(relationPk.isEmpty()){
              if (relationMetaData.getTableInfo().havePK()){
                  relationPk = relationMetaData.getTableInfo().getKeyColumn();
              }
          }
        //如果主标没有设置关联字段,默认按目标表的名称加_id
        if(selfPk.isEmpty()){
            selfPk = relationMetaData.getTableInfo().getTableName() + "_id";
        }
        self.setRelationFieldKey(selfPk);
        relationMetaData.setRelationFieldKey(relationPk);
        //寻找主键对应的实体类成员变量名称
        self.setRelationField(primaryKeyNameSearchFiled(selfPk, ReflectionFieldUtils.getAllFields(self.getReflectClass())));
        relationMetaData.setRelationField(primaryKeyNameSearchFiled(relationPk, ReflectionFieldUtils.getAllFields(relationMetaData.getReflectClass())));

     }

    /**
     * 通过主键名称,寻找对应的成员属性
     * @param name 名称
     * @param fields 属性
     * @return 属性
     */
    private static Field primaryKeyNameSearchFiled(String name ,List<Field> fields) {
        Field targetField = null;
        for (Field field : fields) {
            for (Annotation annotation : field.getAnnotations()) {
                if (annotation.annotationType().equals(TableField.class)){
                    TableField tableField = (TableField) annotation;

                    if (name.equals(tableField.value())){
                        targetField = field;
                    }
                }
            }
        }
        if(targetField == null){
            for (Field field : fields) {
                if (field.getName().equals(StrUtil.toCamelCase(name))){
                    targetField = field;
                }
            }
        }
        return  targetField;
    }

}

