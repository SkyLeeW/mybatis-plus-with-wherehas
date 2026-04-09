package io.github.skyleew.relationmapping.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.skyleew.relationmapping.mapper.RawSqlMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * 校验关联 SQL 参数绑定辅助逻辑，确保字符串主键不会再被直接拼接进 IN 子句。
 */
class RelationSqlServiceTest {

    /**
     * IN 条件占位符应按顺序生成，避免不同类型主键直接内联到 SQL。
     */
    @Test
    @DisplayName("buildInExpression 应生成顺序绑定占位符")
    void buildInExpressionShouldCreateIndexedPlaceholders() throws Exception {
        RelationSqlService relationSqlService = new RelationSqlService(new ObjectMapper(), createNoopRawSqlMapper());
        Method method = RelationSqlService.class.getDeclaredMethod("buildInExpression", String.class, String.class, List.class);
        method.setAccessible(true);

        String expression = (String) method.invoke(relationSqlService, "parent_code", "relationIds", List.of("A001", "B002"));

        assertEquals("parent_code IN (#{relationIds[0]}, #{relationIds[1]})", expression);
    }

    /**
     * 绑定参数上下文应保存真实列表对象，供 XML 中的顺序占位符安全取值。
     */
    @Test
    @DisplayName("putNamedCollectionParameter 应写入绑定参数列表")
    void putNamedCollectionParameterShouldStoreValues() throws Exception {
        RelationSqlService relationSqlService = new RelationSqlService(new ObjectMapper(), createNoopRawSqlMapper());
        Method method = RelationSqlService.class.getDeclaredMethod("putNamedCollectionParameter", HashMap.class, String.class, List.class);
        method.setAccessible(true);

        HashMap<String, Object> execute = new HashMap<>();
        List<String> relationIds = List.of("A001", "B002");
        method.invoke(relationSqlService, execute, "relationIds", relationIds);

        assertEquals(relationIds, execute.get("relationIds"));
    }

    /**
     * 构造一个不会真正执行 SQL 的 RawSqlMapper，避免测试依赖数据库环境。
     *
     * @return 空实现的 Mapper 代理
     */
    private RawSqlMapper createNoopRawSqlMapper() {
        return (RawSqlMapper) Proxy.newProxyInstance(
            RawSqlMapper.class.getClassLoader(),
            new Class<?>[]{RawSqlMapper.class},
            new NoopRawSqlMapperInvocationHandler()
        );
    }

    /**
     * 所有 Mapper 方法统一返回默认值，让测试只关注 RelationSqlService 的参数拼装逻辑。
     */
    private static final class NoopRawSqlMapperInvocationHandler implements InvocationHandler {

        /**
         * 统一给 RawSqlMapper 的所有方法返回默认值，避免引入多余测试噪音。
         *
         * @param proxy 代理对象
         * @param method 当前方法
         * @param args 当前参数
         * @return 默认返回值
         */
        @Override
        public Object invoke(Object proxy, Method method, Object[] args) {
            if (List.class.isAssignableFrom(method.getReturnType())) {
                return Collections.emptyList();
            }
            if (method.getReturnType() == boolean.class) {
                return false;
            }
            if (method.getReturnType() == int.class || method.getReturnType() == long.class || method.getReturnType() == short.class || method.getReturnType() == byte.class) {
                return 0;
            }
            return null;
        }
    }
}
