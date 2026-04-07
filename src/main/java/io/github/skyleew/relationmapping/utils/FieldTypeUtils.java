package io.github.skyleew.relationmapping.utils;

import java.lang.reflect.Field;
import java.util.Arrays;
import java.util.List;

/**
 * 提供字段类型判定相关的静态工具方法。
 */
public class FieldTypeUtils {
    // 存储基础类型和 String 类型对应的类对象
    private static final List<Class<?>> PRIMITIVE_AND_STRING_TYPES = Arrays.asList(
        byte.class, short.class, int.class, long.class,
        float.class, double.class, char.class, boolean.class,
        Byte.class, Short.class, Integer.class, Long.class,
        Float.class, Double.class, Character.class, Boolean.class,
        String.class
    );

    /**
     * 判断传入的 Field 对应的类型是否为基础类型（包括包装类）或 String 类型
     * @param field 要判断的 Field 对象
     * @return 如果是基础类型或 String 类型返回 true，否则返回 false
     */
    public static boolean isPrimitive(Field field) {
        if (field == null) {
            return false;
        }
        // 获取字段的类型
        Class<?> fieldType = field.getType();
        // 检查该类型是否在预定义的类型列表中
        return PRIMITIVE_AND_STRING_TYPES.contains(fieldType);
    }
}

