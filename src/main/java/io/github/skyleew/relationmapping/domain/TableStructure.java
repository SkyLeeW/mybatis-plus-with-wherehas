package io.github.skyleew.relationmapping.domain;

import com.baomidou.mybatisplus.core.metadata.TableInfo;
import lombok.Data;

import java.lang.reflect.Field;

@Data
public class TableStructure {
    /**
     * 有注解的字段
     */
    private Field annotationField;

    /**
     * 实体表的信息
     */
    private TableInfo tableInfo;



    /**
     * 关联字段名称
     */
    private String  relationFieldKey;


    /**
     * 关联属性
     */
    private  Field relationField;

    /**
     * 主键
     */
    private Field primaryKeyField;


    /**
     * 反射的class
     */
    private Class<?> reflectClass;

    private String ids;

}

