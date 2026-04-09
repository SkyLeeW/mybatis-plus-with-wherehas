package io.github.skyleew.relationmapping.utils;

import com.baomidou.mybatisplus.core.metadata.TableInfo;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import io.github.skyleew.relationmapping.annotation.RelationModel;

import java.lang.reflect.Field;
import java.util.Arrays;

/**
 * 提供数据表主键字段解析相关的通用工具方法。
 */
public class DbUtil {
    /**
     * 获取实体对应的主键字段名，优先读取关联注解声明，再回退到表元数据主键列。
     *
     * @param clazz 需要解析主键字段的实体类型
     * @return 返回主键字段名，若未解析到则返回空字符串
     */
    public static String  getTablePkName(Class<?> clazz) {
        String targetFieldValue = "";
        for (Field field : ReflectionFieldUtils.getAllFields(clazz)) {
            targetFieldValue = Arrays.stream(field.getDeclaredAnnotations())
                .filter(anno -> anno.annotationType().equals(RelationModel.class))
                .findFirst()
                .map(anno -> {
                    // 根据注解类型提取字段值
                    if (anno instanceof RelationModel) {
                        return ((RelationModel) anno).field();  // 提取 OneToOne 的 field
                    }
                    return "";
                })
                .orElse("");  // 未匹配到注解时返回空字符串
        }
        if(!targetFieldValue.isEmpty()){
            return  targetFieldValue;
        }
        TableInfo tableInfo = TableInfoHelper.getTableInfo(clazz);
        if(tableInfo.havePK()){
            return tableInfo.getKeyColumn();
        }

        return "";
    }
}

