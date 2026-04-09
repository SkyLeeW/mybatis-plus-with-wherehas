package io.github.skyleew.relationmapping.utils;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 统一处理继承树字段遍历与查找，避免各处只看当前类导致父类字段失效。
 */
public final class ReflectionFieldUtils {

    /**
     * 缓存实体继承树中的全部字段，减少高频场景重复反射扫描成本。
     */
    private static final Map<Class<?>, List<Field>> ALL_FIELDS_CACHE = new ConcurrentHashMap<>();

    /**
     * 工具类不允许实例化，避免出现无意义状态。
     */
    private ReflectionFieldUtils() {
    }

    /**
     * 获取实体及其父类的全部字段，顺序保持“子类在前，父类在后”。
     *
     * @param clazz 目标类型
     * @return 全量字段列表
     */
    public static List<Field> getAllFields(Class<?> clazz) {
        if (clazz == null) {
            return Collections.emptyList();
        }
        return ALL_FIELDS_CACHE.computeIfAbsent(clazz, ReflectionFieldUtils::collectAllFields);
    }

    /**
     * 按字段名在继承树中查找字段，找到后直接返回原始 Field 对象。
     *
     * @param clazz 目标类型
     * @param fieldName 字段名
     * @return 命中的字段，不存在时返回 null
     */
    public static Field findField(Class<?> clazz, String fieldName) {
        if (clazz == null || fieldName == null || fieldName.isEmpty()) {
            return null;
        }
        for (Field field : getAllFields(clazz)) {
            if (fieldName.equals(field.getName())) {
                return field;
            }
        }
        return null;
    }

    /**
     * 收集当前类到 Object 之间的全部声明字段，保证父类字段也能参与映射。
     *
     * @param clazz 目标类型
     * @return 采集完成的字段列表
     */
    private static List<Field> collectAllFields(Class<?> clazz) {
        List<Field> fields = new ArrayList<>();
        Class<?> current = clazz;
        while (current != null && current != Object.class) {
            Collections.addAll(fields, current.getDeclaredFields());
            current = current.getSuperclass();
        }
        return fields;
    }
}
