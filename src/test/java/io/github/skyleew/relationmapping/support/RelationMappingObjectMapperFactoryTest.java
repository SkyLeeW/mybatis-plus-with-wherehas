package io.github.skyleew.relationmapping.support;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.OffsetDateTime;
import java.time.OffsetTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TimeZone;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * 校验库内 ObjectMapper 的时间规则和宽松字段映射规则，避免关联查询与 VO 转换再次回退。
 */
class RelationMappingObjectMapperFactoryTest {

    /**
     * 记录测试前的默认时区，避免污染其它测试或当前开发机环境。
     */
    private TimeZone originalTimeZone;

    /**
     * 在每个测试开始前固定时区并清空缓存，保证断言结果稳定可重复。
     *
     * @throws ReflectiveOperationException 反射清理缓存失败时抛出异常
     */
    @BeforeEach
    void setUp() throws ReflectiveOperationException {
        originalTimeZone = TimeZone.getDefault();
        TimeZone.setDefault(TimeZone.getTimeZone("UTC"));
        resetObjectMapperCache();
    }

    /**
     * 在每个测试结束后恢复默认时区并清空缓存，避免静态状态泄漏到后续流程。
     *
     * @throws ReflectiveOperationException 反射清理缓存失败时抛出异常
     */
    @AfterEach
    void tearDown() throws ReflectiveOperationException {
        TimeZone.setDefault(originalTimeZone);
        resetObjectMapperCache();
    }

    /**
     * 复位工厂里的静态缓存，确保每个测试都按当前时区重新构建 ObjectMapper。
     *
     * @throws ReflectiveOperationException 反射写入静态字段失败时抛出异常
     */
    private void resetObjectMapperCache() throws ReflectiveOperationException {
        clearStaticField("cachedApplicationMapper");
        clearStaticField("strictObjectMapper");
        clearStaticField("lenientObjectMapper");
    }

    /**
     * 将指定静态字段重置为 null，确保工厂不会继续复用旧缓存。
     *
     * @param fieldName 需要清空的字段名
     * @throws ReflectiveOperationException 反射写字段失败时抛出异常
     */
    private void clearStaticField(String fieldName) throws ReflectiveOperationException {
        Field field = RelationMappingObjectMapperFactory.class.getDeclaredField(fieldName);
        field.setAccessible(true);
        field.set(null, null);
    }

    /**
     * 验证实体转 VO 时会忽略 VO 未声明字段，同时仍能完成时间字段转换。
     */
    @Test
    @DisplayName("BeanConversionUtils 应忽略目标 VO 未声明字段")
    void beanConversionShouldIgnoreUnknownFields() {
        Map<String, Object> source = new LinkedHashMap<>();
        source.put("id", 1L);
        source.put("createDept", "研发部");
        source.put("createTime", "2026-12-23 00:00:00");

        List<RepairOrderVo> result = BeanConversionUtils.convertToList(List.of(source), RepairOrderVo.class);

        assertEquals(1, result.size());
        assertEquals(1L, result.get(0).id);
        assertEquals(LocalDateTime.of(2026, 12, 23, 0, 0, 0), result.get(0).createTime);
    }

    /**
     * 验证关联查询里的 JSON 反序列化可直接接收数据库常见时间字符串。
     */
    @Test
    @DisplayName("JsonSupport 应支持数据库常见 LocalDateTime 字符串")
    void jsonSupportShouldParseSqlLocalDateTimeString() {
        AssetManagement assetManagement = JsonSupport.parseObject(
            "{\"idleStartDate\":\"2026-12-23 00:00:00\"}",
            AssetManagement.class
        );

        assertEquals(LocalDateTime.of(2026, 12, 23, 0, 0, 0), assetManagement.idleStartDate);
    }

    /**
     * 验证时间类型统一规则不会只修一个 LocalDateTime，其它常见时间类型也能稳定工作。
     */
    @Test
    @DisplayName("JsonSupport 应统一支持常见 java.time 类型")
    void jsonSupportShouldParseCommonJavaTimeTypes() {
        TemporalCarrier temporalCarrier = JsonSupport.parseObject(
            """
                {
                  "instantValue":"2026-12-23 00:00:00",
                  "localDateTimeValue":1775196252000,
                  "localDateValue":"2026-12-23 12:34:56",
                  "localTimeValue":"2026-12-23 12:34:56",
                  "offsetDateTimeValue":"2026-12-23 00:00:00",
                  "zonedDateTimeValue":"2026-12-23 00:00:00",
                  "offsetTimeValue":"2026-12-23 12:34:56"
                }
                """,
            TemporalCarrier.class
        );

        assertEquals(Instant.parse("2026-12-23T00:00:00Z"), temporalCarrier.instantValue);
        assertEquals(
            Instant.ofEpochMilli(1775196252000L).atZone(ZoneOffset.UTC).toLocalDateTime(),
            temporalCarrier.localDateTimeValue
        );
        assertEquals(LocalDate.of(2026, 12, 23), temporalCarrier.localDateValue);
        assertEquals(LocalTime.of(12, 34, 56), temporalCarrier.localTimeValue);
        assertEquals(OffsetDateTime.of(2026, 12, 23, 0, 0, 0, 0, ZoneOffset.UTC), temporalCarrier.offsetDateTimeValue);
        assertEquals(ZonedDateTime.of(2026, 12, 23, 0, 0, 0, 0, ZoneId.of("UTC")), temporalCarrier.zonedDateTimeValue);
        assertEquals(OffsetTime.of(12, 34, 56, 0, ZoneOffset.UTC), temporalCarrier.offsetTimeValue);
    }

    /**
     * 模拟业务侧 VO，只声明实际需要的字段，用来校验宽松转换行为。
     */
    private static final class RepairOrderVo {

        /**
         * 主键字段用于校验基础属性映射是否正常。
         */
        public Long id;

        /**
         * 创建时间字段用于校验字符串到 LocalDateTime 的转换是否正常。
         */
        public LocalDateTime createTime;
    }

    /**
     * 模拟关联实体，用来复现 HasRelationService 里的时间反序列化场景。
     */
    private static final class AssetManagement {

        /**
         * 空闲开始时间字段用于校验数据库常见日期时间字符串解析。
         */
        public LocalDateTime idleStartDate;
    }

    /**
     * 聚合常见 java.time 类型，统一校验本库自定义时间规则的覆盖范围。
     */
    private static final class TemporalCarrier {

        /**
         * 绝对时间字段用于校验本地日期时间字符串到 Instant 的转换。
         */
        public Instant instantValue;

        /**
         * 本地日期时间字段用于校验 epoch 毫秒仍然可正常转换。
         */
        public LocalDateTime localDateTimeValue;

        /**
         * 本地日期字段用于校验日期时间字符串提取日期部分。
         */
        public LocalDate localDateValue;

        /**
         * 本地时间字段用于校验日期时间字符串提取时间部分。
         */
        public LocalTime localTimeValue;

        /**
         * 偏移日期时间字段用于校验本地日期时间字符串补齐时区偏移。
         */
        public OffsetDateTime offsetDateTimeValue;

        /**
         * 带时区日期时间字段用于校验本地日期时间字符串补齐时区信息。
         */
        public ZonedDateTime zonedDateTimeValue;

        /**
         * 偏移时间字段用于校验本地日期时间字符串补齐偏移后再提取时间。
         */
        public OffsetTime offsetTimeValue;
    }
}
