package io.github.skyleew.relationmapping.utils;

import com.baomidou.mybatisplus.core.metadata.TableInfo;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import io.github.skyleew.relationmapping.annotation.RelationModel;

import java.lang.reflect.Field;
import java.util.Arrays;

public class DbUtil {
    public static String  getTablePkName(Class<?> clazz) {
        String targetFieldValue = "";
        for (Field field : clazz.getDeclaredFields()) {
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

