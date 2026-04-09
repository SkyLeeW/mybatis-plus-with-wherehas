package io.github.skyleew.relationmapping.domain;

import com.baomidou.mybatisplus.core.metadata.TableInfo;
import lombok.Data;

import java.util.ArrayList;
import java.util.List;
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

    /**
     * 兼容旧逻辑保留的关联 id 字符串，仅用于调试或过渡，不再参与 SQL 拼接。
     */
    private String ids;

    /**
     * 当前查询真实参与绑定的关联值列表，避免把值直接拼进 SQL。
     */
    private List<Object> relationValues = new ArrayList<>();

}

