package io.github.skyleew.relationmapping.support;

import com.fasterxml.jackson.databind.JavaType;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.json.JsonMapper;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * 基于 Jackson 的对象转换工具，负责将实体稳定转换为目标对象或目标列表。
 */
public final class BeanConversionUtils {

    /**
     * 统一复用带 JSR310 模块的 ObjectMapper，避免日期时间字段转换失真。
     */
    private static final ObjectMapper OBJECT_MAPPER = JsonMapper.builder()
        .findAndAddModules()
        .build();

    /**
     * 工具类不允许实例化，防止外部错误创建无状态对象。
     */
    private BeanConversionUtils() {
    }

    /**
     * 将单个源对象转换为目标类型实例，空值时直接返回 null。
     *
     * @param source 源对象
     * @param targetClass 目标类型
     * @param <T> 目标泛型
     * @return 转换后的目标对象
     */
    public static <T> T convert(Object source, Class<T> targetClass) {
        if (source == null || targetClass == null) {
            return null;
        }
        return OBJECT_MAPPER.convertValue(source, targetClass);
    }

    /**
     * 将任意集合转换为目标元素类型列表，空集合时返回不可变空列表。
     *
     * @param source 源集合对象
     * @param elementClass 目标元素类型
     * @param <T> 目标元素泛型
     * @return 转换后的列表
     */
    public static <T> List<T> convert(List<?> source, Class<T> elementClass) {
        if (source == null || source.isEmpty()) {
            return Collections.emptyList();
        }
        JavaType targetType = OBJECT_MAPPER.getTypeFactory().constructCollectionType(List.class, elementClass);
        return OBJECT_MAPPER.convertValue(source, targetType);
    }

    /**
     * 将未知对象按集合语义转换为目标列表，兼容单对象和集合两种输入。
     *
     * @param source 源对象
     * @param elementClass 目标元素类型
     * @param <T> 目标元素泛型
     * @return 转换后的列表
     */
    public static <T> List<T> convertToList(Object source, Class<T> elementClass) {
        if (source == null) {
            return Collections.emptyList();
        }
        if (source instanceof List<?> sourceList) {
            return convert(sourceList, elementClass);
        }
        List<T> result = new ArrayList<>(1);
        result.add(convert(source, elementClass));
        return result;
    }
}

