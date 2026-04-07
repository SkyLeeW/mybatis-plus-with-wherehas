package io.github.skyleew.relationmapping.support;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.json.JsonMapper;

/**
 * 统一封装 Jackson 序列化与反序列化，避免模块继续依赖内部 JSON 工具。
 */
public final class JsonSupport {

    /**
     * 统一复用带扩展模块的 ObjectMapper，确保时间类型与集合类型可直接处理。
     */
    private static final ObjectMapper OBJECT_MAPPER = JsonMapper.builder()
        .findAndAddModules()
        .build();

    /**
     * 工具类不允许实例化，避免出现无意义的状态对象。
     */
    private JsonSupport() {
    }

    /**
     * 将任意对象序列化为 JSON 字符串，失败时抛出参数非法异常以暴露真实问题。
     *
     * @param source 源对象
     * @return JSON 字符串
     */
    public static String toJsonString(Object source) {
        try {
            return OBJECT_MAPPER.writeValueAsString(source);
        } catch (JsonProcessingException exception) {
            throw new IllegalArgumentException("对象序列化为 JSON 失败", exception);
        }
    }

    /**
     * 将 JSON 字符串解析为目标类型对象，失败时抛出参数非法异常以中断错误流程。
     *
     * @param json JSON 字符串
     * @param targetClass 目标类型
     * @param <T> 目标泛型
     * @return 解析后的对象
     */
    public static <T> T parseObject(String json, Class<T> targetClass) {
        try {
            return OBJECT_MAPPER.readValue(json, targetClass);
        } catch (JsonProcessingException exception) {
            throw new IllegalArgumentException("JSON 反序列化失败", exception);
        }
    }
}

