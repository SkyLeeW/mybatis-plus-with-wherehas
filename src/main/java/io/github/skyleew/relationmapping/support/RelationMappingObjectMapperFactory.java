package io.github.skyleew.relationmapping.support;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.JsonToken;
import com.fasterxml.jackson.databind.DeserializationContext;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.deser.std.StdDeserializer;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.fasterxml.jackson.databind.module.SimpleModule;
import com.fasterxml.jackson.datatype.jsr310.deser.InstantDeserializer;
import com.fasterxml.jackson.datatype.jsr310.deser.LocalDateDeserializer;
import com.fasterxml.jackson.datatype.jsr310.deser.LocalDateTimeDeserializer;
import com.fasterxml.jackson.datatype.jsr310.deser.LocalTimeDeserializer;
import com.fasterxml.jackson.datatype.jsr310.deser.OffsetTimeDeserializer;

import java.io.IOException;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.OffsetDateTime;
import java.time.OffsetTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeFormatterBuilder;
import java.time.format.DateTimeParseException;
import java.time.temporal.ChronoField;
import java.util.TimeZone;

/**
 * 统一构建本库使用的 ObjectMapper，确保 VO 转换与 JSON 解析的时间字段规则保持一致。
 */
public final class RelationMappingObjectMapperFactory {

    /**
     * 统一兼容数据库常见的日期时间字符串格式，支持空格分隔与可选秒、小数秒。
     */
    private static final DateTimeFormatter SQL_LOCAL_DATE_TIME_FORMATTER = new DateTimeFormatterBuilder()
        .appendPattern("yyyy-MM-dd HH:mm")
        .optionalStart()
        .appendLiteral(':')
        .appendValue(ChronoField.SECOND_OF_MINUTE, 2)
        .optionalStart()
        .appendFraction(ChronoField.NANO_OF_SECOND, 1, 9, true)
        .optionalEnd()
        .optionalEnd()
        .toFormatter();

    /**
     * 统一兼容数据库常见的时间字符串格式，支持可选秒与小数秒。
     */
    private static final DateTimeFormatter SQL_LOCAL_TIME_FORMATTER = new DateTimeFormatterBuilder()
        .appendPattern("HH:mm")
        .optionalStart()
        .appendLiteral(':')
        .appendValue(ChronoField.SECOND_OF_MINUTE, 2)
        .optionalStart()
        .appendFraction(ChronoField.NANO_OF_SECOND, 1, 9, true)
        .optionalEnd()
        .optionalEnd()
        .toFormatter();

    /**
     * 复用缓存时的同步锁，避免并发环境下重复构建 ObjectMapper。
     */
    private static final Object LOCK = new Object();

    /**
     * 记录当前缓存所基于的应用级 ObjectMapper，便于配置变化时整体重建。
     */
    private static volatile ObjectMapper cachedApplicationMapper;

    /**
     * 严格模式下的 ObjectMapper，用于库内常规 JSON 解析。
     */
    private static volatile ObjectMapper strictObjectMapper;

    /**
     * 宽松模式下的 ObjectMapper，用于实体转 VO 时忽略目标未声明字段。
     */
    private static volatile ObjectMapper lenientObjectMapper;

    /**
     * 工厂类不允许实例化，避免外部持有无意义状态。
     */
    private RelationMappingObjectMapperFactory() {
    }

    /**
     * 获取严格模式的 ObjectMapper，保留默认未知字段校验。
     *
     * @return 严格模式 ObjectMapper
     */
    public static ObjectMapper getStrictObjectMapper() {
        return getOrCreateObjectMapper(false);
    }

    /**
     * 获取宽松模式的 ObjectMapper，允许源对象字段多于目标对象字段。
     *
     * @return 宽松模式 ObjectMapper
     */
    public static ObjectMapper getLenientObjectMapper() {
        return getOrCreateObjectMapper(true);
    }

    /**
     * 按需获取缓存中的 ObjectMapper，并在应用级配置变化后自动重建。
     *
     * @param ignoreUnknownProperties 是否忽略目标未知字段
     * @return 对应模式的 ObjectMapper
     */
    private static ObjectMapper getOrCreateObjectMapper(boolean ignoreUnknownProperties) {
        ObjectMapper applicationObjectMapper = resolveApplicationObjectMapper();
        if (canReuseCachedMapper(applicationObjectMapper, ignoreUnknownProperties)) {
            return ignoreUnknownProperties ? lenientObjectMapper : strictObjectMapper;
        }
        synchronized (LOCK) {
            if (!canReuseCachedMapper(applicationObjectMapper, ignoreUnknownProperties)) {
                rebuildObjectMappers(applicationObjectMapper);
            }
        }
        return ignoreUnknownProperties ? lenientObjectMapper : strictObjectMapper;
    }

    /**
     * 判断当前缓存是否仍可直接复用，避免无意义重建。
     *
     * @param applicationObjectMapper 应用级 ObjectMapper
     * @param ignoreUnknownProperties 目标模式
     * @return 可复用时返回 true
     */
    private static boolean canReuseCachedMapper(ObjectMapper applicationObjectMapper, boolean ignoreUnknownProperties) {
        if (applicationObjectMapper != cachedApplicationMapper) {
            return false;
        }
        return ignoreUnknownProperties ? lenientObjectMapper != null : strictObjectMapper != null;
    }

    /**
     * 基于当前应用级 ObjectMapper 重新构建严格与宽松两套映射器。
     *
     * @param applicationObjectMapper 应用级 ObjectMapper，可能为 null
     */
    private static void rebuildObjectMappers(ObjectMapper applicationObjectMapper) {
        cachedApplicationMapper = applicationObjectMapper;
        strictObjectMapper = buildObjectMapper(applicationObjectMapper, false);
        lenientObjectMapper = buildObjectMapper(applicationObjectMapper, true);
    }

    /**
     * 构建单个 ObjectMapper，并注册本库统一的时间反序列化规则。
     *
     * @param applicationObjectMapper 应用级 ObjectMapper，可能为 null
     * @param ignoreUnknownProperties 是否忽略目标未知字段
     * @return 构建完成的 ObjectMapper
     */
    private static ObjectMapper buildObjectMapper(ObjectMapper applicationObjectMapper, boolean ignoreUnknownProperties) {
        ObjectMapper objectMapper = applicationObjectMapper == null
            ? JsonMapper.builder().findAndAddModules().build()
            : applicationObjectMapper.copy();
        objectMapper.registerModule(buildTimeModule());
        objectMapper.configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, !ignoreUnknownProperties);
        return objectMapper;
    }

    /**
     * 优先复用业务工程中的 ObjectMapper，保证时区与全局配置和应用保持一致。
     *
     * @return 应用级 ObjectMapper，不存在时返回 null
     */
    private static ObjectMapper resolveApplicationObjectMapper() {
        try {
            return SpringContextHolder.getBean(ObjectMapper.class);
        } catch (Exception exception) {
            return null;
        }
    }

    /**
     * 注册时间类型扩展规则，使常见时间类型都可稳定接收 epoch 毫秒时间戳。
     *
     * @return 自定义时间模块
     */
    private static SimpleModule buildTimeModule() {
        SimpleModule module = new SimpleModule("relation-mapping-time-module");
        module.addDeserializer(Instant.class, new EpochMillisInstantDeserializer());
        module.addDeserializer(LocalDateTime.class, new EpochMillisLocalDateTimeDeserializer());
        module.addDeserializer(LocalDate.class, new EpochMillisLocalDateDeserializer());
        module.addDeserializer(LocalTime.class, new EpochMillisLocalTimeDeserializer());
        module.addDeserializer(OffsetDateTime.class, new EpochMillisOffsetDateTimeDeserializer());
        module.addDeserializer(ZonedDateTime.class, new EpochMillisZonedDateTimeDeserializer());
        module.addDeserializer(OffsetTime.class, new EpochMillisOffsetTimeDeserializer());
        return module;
    }

    /**
     * 根据 Jackson 上下文解析时区，优先使用应用配置，其次回退为 JVM 默认时区。
     *
     * @param context Jackson 反序列化上下文
     * @return 生效时区
     */
    private static ZoneId resolveZoneId(DeserializationContext context) {
        TimeZone timeZone = context.getTimeZone();
        if (timeZone != null) {
            return timeZone.toZoneId();
        }
        return ZoneId.systemDefault();
    }

    /**
     * 将字符串解析为 epoch 毫秒时间戳，非纯数字字符串时返回 null。
     *
     * @param rawValue 原始字符串
     * @return epoch 毫秒值，无法解析时返回 null
     */
    private static Long parseEpochMillis(String rawValue) {
        if (rawValue == null) {
            return null;
        }
        String trimmedValue = rawValue.trim();
        if (trimmedValue.isEmpty()) {
            return null;
        }
        for (int index = 0; index < trimmedValue.length(); index++) {
            char currentChar = trimmedValue.charAt(index);
            if (index == 0 && (currentChar == '-' || currentChar == '+')) {
                continue;
            }
            if (!Character.isDigit(currentChar)) {
                return null;
            }
        }
        return Long.parseLong(trimmedValue);
    }

    /**
     * 标准化字符串输入，统一去掉前后空白，空串直接视为无有效值。
     *
     * @param rawValue 原始字符串
     * @return 清洗后的字符串，无有效值时返回 null
     */
    private static String normalizeText(String rawValue) {
        if (rawValue == null) {
            return null;
        }
        String trimmedValue = rawValue.trim();
        return trimmedValue.isEmpty() ? null : trimmedValue;
    }

    /**
     * 使用给定解析器尝试解析时间字符串，失败时返回 null，避免污染主流程异常信息。
     *
     * @param rawValue 原始字符串
     * @param parser 时间解析器
     * @param <T> 目标类型
     * @return 解析结果，无法解析时返回 null
     */
    private static <T> T tryParse(String rawValue, TemporalStringParser<T> parser) {
        if (rawValue == null) {
            return null;
        }
        try {
            return parser.parse(rawValue);
        } catch (DateTimeParseException exception) {
            return null;
        }
    }

    /**
     * 优先解析 ISO 本地时间，再兼容数据库常见的空格分隔时间格式。
     *
     * @param rawValue 原始字符串
     * @return 本地日期时间，无法解析时返回 null
     */
    private static LocalDateTime tryParseLocalDateTime(String rawValue) {
        LocalDateTime localDateTime = tryParse(rawValue, LocalDateTime::parse);
        if (localDateTime != null) {
            return localDateTime;
        }
        return tryParse(rawValue, value -> LocalDateTime.parse(value, SQL_LOCAL_DATE_TIME_FORMATTER));
    }

    /**
     * 兼容日期字符串以及数据库输出的日期时间字符串，并统一提取日期部分。
     *
     * @param rawValue 原始字符串
     * @return 本地日期，无法解析时返回 null
     */
    private static LocalDate tryParseLocalDate(String rawValue) {
        LocalDate localDate = tryParse(rawValue, LocalDate::parse);
        if (localDate != null) {
            return localDate;
        }
        LocalDateTime localDateTime = tryParseLocalDateTime(rawValue);
        return localDateTime == null ? null : localDateTime.toLocalDate();
    }

    /**
     * 兼容时间字符串以及数据库输出的日期时间字符串，并统一提取时间部分。
     *
     * @param rawValue 原始字符串
     * @return 本地时间，无法解析时返回 null
     */
    private static LocalTime tryParseLocalTime(String rawValue) {
        LocalTime localTime = tryParse(rawValue, LocalTime::parse);
        if (localTime != null) {
            return localTime;
        }
        localTime = tryParse(rawValue, value -> LocalTime.parse(value, SQL_LOCAL_TIME_FORMATTER));
        if (localTime != null) {
            return localTime;
        }
        LocalDateTime localDateTime = tryParseLocalDateTime(rawValue);
        return localDateTime == null ? null : localDateTime.toLocalTime();
    }

    /**
     * 兼容带偏移量字符串和本地日期时间字符串，后者按上下文时区补齐偏移量。
     *
     * @param rawValue 原始字符串
     * @param context 反序列化上下文
     * @return 带偏移量的日期时间，无法解析时返回 null
     */
    private static OffsetDateTime tryParseOffsetDateTime(String rawValue, DeserializationContext context) {
        OffsetDateTime offsetDateTime = tryParse(rawValue, OffsetDateTime::parse);
        if (offsetDateTime != null) {
            return offsetDateTime;
        }
        LocalDateTime localDateTime = tryParseLocalDateTime(rawValue);
        if (localDateTime == null) {
            return null;
        }
        return localDateTime.atZone(resolveZoneId(context)).toOffsetDateTime();
    }

    /**
     * 兼容带时区字符串和本地日期时间字符串，后者按上下文时区补齐时区信息。
     *
     * @param rawValue 原始字符串
     * @param context 反序列化上下文
     * @return 带时区的日期时间，无法解析时返回 null
     */
    private static ZonedDateTime tryParseZonedDateTime(String rawValue, DeserializationContext context) {
        ZonedDateTime zonedDateTime = tryParse(rawValue, ZonedDateTime::parse);
        if (zonedDateTime != null) {
            return zonedDateTime;
        }
        OffsetDateTime offsetDateTime = tryParse(rawValue, OffsetDateTime::parse);
        if (offsetDateTime != null) {
            return offsetDateTime.toZonedDateTime();
        }
        LocalDateTime localDateTime = tryParseLocalDateTime(rawValue);
        if (localDateTime == null) {
            return null;
        }
        return localDateTime.atZone(resolveZoneId(context));
    }

    /**
     * 兼容绝对时间字符串和本地日期时间字符串，后者按上下文时区解释为瞬时值。
     *
     * @param rawValue 原始字符串
     * @param context 反序列化上下文
     * @return 瞬时值，无法解析时返回 null
     */
    private static Instant tryParseInstant(String rawValue, DeserializationContext context) {
        Instant instant = tryParse(rawValue, Instant::parse);
        if (instant != null) {
            return instant;
        }
        OffsetDateTime offsetDateTime = tryParseOffsetDateTime(rawValue, context);
        return offsetDateTime == null ? null : offsetDateTime.toInstant();
    }

    /**
     * 兼容带偏移量时间字符串和本地日期时间字符串，后者按上下文时区补齐当天偏移量。
     *
     * @param rawValue 原始字符串
     * @param context 反序列化上下文
     * @return 带偏移量的时间，无法解析时返回 null
     */
    private static OffsetTime tryParseOffsetTime(String rawValue, DeserializationContext context) {
        OffsetTime offsetTime = tryParse(rawValue, OffsetTime::parse);
        if (offsetTime != null) {
            return offsetTime;
        }
        OffsetDateTime offsetDateTime = tryParseOffsetDateTime(rawValue, context);
        return offsetDateTime == null ? null : offsetDateTime.toOffsetTime();
    }

    /**
     * 将 epoch 毫秒时间戳转换为 Instant，保证瞬时值语义不发生偏移。
     */
    private static Instant toInstant(long epochMillis) {
        return Instant.ofEpochMilli(epochMillis);
    }

    /**
     * 将 epoch 毫秒时间戳转换为带时区的日期时间，统一复用应用级时区配置。
     */
    private static ZonedDateTime toZonedDateTime(long epochMillis, DeserializationContext context) {
        return toInstant(epochMillis).atZone(resolveZoneId(context));
    }

    /**
     * 将 epoch 毫秒时间戳转换为带偏移量的日期时间，偏移量从应用级时区规则中推导。
     */
    private static OffsetDateTime toOffsetDateTime(long epochMillis, DeserializationContext context) {
        return toZonedDateTime(epochMillis, context).toOffsetDateTime();
    }

    /**
     * 将数字或纯数字字符串解析为 epoch 毫秒后执行自定义转换，非数字输入继续委托标准反序列化器。
     *
     * @param parser JSON 解析器
     * @param context 反序列化上下文
     * @param converter epoch 毫秒转换器
     * @param stringParser 普通字符串解析器
     * @param fallback 标准反序列化回调
     * @param <T> 目标时间类型
     * @return 解析后的目标对象
     * @throws IOException 解析失败时抛出 IO 异常
     */
    private static <T> T deserializeEpochMillisAware(JsonParser parser,
                                                     DeserializationContext context,
                                                     EpochMillisConverter<T> converter,
                                                     StringValueParser<T> stringParser,
                                                     JacksonDeserializer<T> fallback) throws IOException {
        if (parser.currentToken() == JsonToken.VALUE_NUMBER_INT) {
            return converter.convert(parser.getLongValue(), context);
        }
        if (parser.currentToken() == JsonToken.VALUE_STRING) {
            String rawValue = normalizeText(parser.getText());
            Long epochMillis = parseEpochMillis(rawValue);
            if (epochMillis != null) {
                return converter.convert(epochMillis, context);
            }
            T parsedValue = stringParser.parse(rawValue, context);
            if (parsedValue != null) {
                return parsedValue;
            }
        }
        return fallback.deserialize(parser, context);
    }

    /**
     * 约束 epoch 毫秒到目标时间类型的转换逻辑，避免在每个反序列化器中重复样板代码。
     */
    @FunctionalInterface
    private interface EpochMillisConverter<T> {

        /**
         * 将 epoch 毫秒和上下文时区转换为目标对象。
         *
         * @param epochMillis epoch 毫秒值
         * @param context 反序列化上下文
         * @return 目标对象
         * @throws IOException 转换失败时抛出 IO 异常
         */
        T convert(long epochMillis, DeserializationContext context) throws IOException;
    }

    /**
     * 约束委托给 Jackson 标准反序列化器的调用签名。
     */
    @FunctionalInterface
    private interface JacksonDeserializer<T> {

        /**
         * 使用 Jackson 默认规则解析输入。
         *
         * @param parser JSON 解析器
         * @param context 反序列化上下文
         * @return 解析结果
         * @throws IOException 解析失败时抛出 IO 异常
         */
        T deserialize(JsonParser parser, DeserializationContext context) throws IOException;
    }

    /**
     * 约束字符串时间值的解析逻辑，统一返回 null 表示当前格式不支持。
     */
    @FunctionalInterface
    private interface StringValueParser<T> {

        /**
         * 解析字符串时间值。
         *
         * @param rawValue 原始字符串
         * @param context 反序列化上下文
         * @return 解析结果，当前解析器不支持时返回 null
         * @throws IOException 解析失败时抛出 IO 异常
         */
        T parse(String rawValue, DeserializationContext context) throws IOException;
    }

    /**
     * 约束无上下文的时间字符串解析逻辑，统一屏蔽格式不匹配异常。
     */
    @FunctionalInterface
    private interface TemporalStringParser<T> {

        /**
         * 解析时间字符串。
         *
         * @param rawValue 原始字符串
         * @return 解析后的目标对象
         */
        T parse(String rawValue);
    }

    /**
     * 将 epoch 毫秒时间戳稳定转换为 Instant。
     */
    private static final class EpochMillisInstantDeserializer extends StdDeserializer<Instant> {

        /**
         * 固定声明当前反序列化器负责 Instant 类型。
         */
        private EpochMillisInstantDeserializer() {
            super(Instant.class);
        }

        /**
         * 允许 Instant 接收 epoch 毫秒数值，避免 Jackson 把毫秒值误当成秒值。
         *
         * @param parser JSON 解析器
         * @param context 反序列化上下文
         * @return 解析后的 Instant
         * @throws IOException 解析失败时抛出 IO 异常
         */
        @Override
        public Instant deserialize(JsonParser parser, DeserializationContext context) throws IOException {
            return deserializeEpochMillisAware(
                parser,
                context,
                (epochMillis, ctx) -> toInstant(epochMillis),
                RelationMappingObjectMapperFactory::tryParseInstant,
                InstantDeserializer.INSTANT::deserialize
            );
        }
    }

    /**
     * 将 epoch 毫秒时间戳稳定转换为 LocalDateTime，并按上下文时区解释瞬时值。
     */
    private static final class EpochMillisLocalDateTimeDeserializer extends StdDeserializer<LocalDateTime> {

        /**
         * 固定声明当前反序列化器负责 LocalDateTime 类型。
         */
        private EpochMillisLocalDateTimeDeserializer() {
            super(LocalDateTime.class);
        }

        /**
         * 允许 LocalDateTime 接收 epoch 毫秒数值，其他格式继续走 Jackson 标准解析逻辑。
         *
         * @param parser JSON 解析器
         * @param context 反序列化上下文
         * @return 解析后的 LocalDateTime
         * @throws IOException 解析失败时抛出 IO 异常
         */
        @Override
        public LocalDateTime deserialize(JsonParser parser, DeserializationContext context) throws IOException {
            return deserializeEpochMillisAware(
                parser,
                context,
                (epochMillis, ctx) -> LocalDateTime.ofInstant(toInstant(epochMillis), resolveZoneId(ctx)),
                (rawValue, ctx) -> tryParseLocalDateTime(rawValue),
                LocalDateTimeDeserializer.INSTANCE::deserialize
            );
        }
    }

    /**
     * 将 epoch 毫秒时间戳稳定转换为 LocalDate，并按上下文时区取本地日期部分。
     */
    private static final class EpochMillisLocalDateDeserializer extends StdDeserializer<LocalDate> {

        /**
         * 固定声明当前反序列化器负责 LocalDate 类型。
         */
        private EpochMillisLocalDateDeserializer() {
            super(LocalDate.class);
        }

        /**
         * 允许 LocalDate 接收 epoch 毫秒数值，其他格式继续走 Jackson 标准解析逻辑。
         *
         * @param parser JSON 解析器
         * @param context 反序列化上下文
         * @return 解析后的 LocalDate
         * @throws IOException 解析失败时抛出 IO 异常
         */
        @Override
        public LocalDate deserialize(JsonParser parser, DeserializationContext context) throws IOException {
            return deserializeEpochMillisAware(
                parser,
                context,
                (epochMillis, ctx) -> toZonedDateTime(epochMillis, ctx).toLocalDate(),
                (rawValue, ctx) -> tryParseLocalDate(rawValue),
                LocalDateDeserializer.INSTANCE::deserialize
            );
        }
    }

    /**
     * 将 epoch 毫秒时间戳稳定转换为 LocalTime，并按上下文时区取本地时间部分。
     */
    private static final class EpochMillisLocalTimeDeserializer extends StdDeserializer<LocalTime> {

        /**
         * 固定声明当前反序列化器负责 LocalTime 类型。
         */
        private EpochMillisLocalTimeDeserializer() {
            super(LocalTime.class);
        }

        /**
         * 允许 LocalTime 接收 epoch 毫秒数值，并稳定提取本地时分秒。
         *
         * @param parser JSON 解析器
         * @param context 反序列化上下文
         * @return 解析后的 LocalTime
         * @throws IOException 解析失败时抛出 IO 异常
         */
        @Override
        public LocalTime deserialize(JsonParser parser, DeserializationContext context) throws IOException {
            return deserializeEpochMillisAware(
                parser,
                context,
                (epochMillis, ctx) -> toZonedDateTime(epochMillis, ctx).toLocalTime(),
                (rawValue, ctx) -> tryParseLocalTime(rawValue),
                LocalTimeDeserializer.INSTANCE::deserialize
            );
        }
    }

    /**
     * 将 epoch 毫秒时间戳稳定转换为 OffsetDateTime，并按应用时区规则推导偏移量。
     */
    private static final class EpochMillisOffsetDateTimeDeserializer extends StdDeserializer<OffsetDateTime> {

        /**
         * 固定声明当前反序列化器负责 OffsetDateTime 类型。
         */
        private EpochMillisOffsetDateTimeDeserializer() {
            super(OffsetDateTime.class);
        }

        /**
         * 允许 OffsetDateTime 接收 epoch 毫秒数值，避免 Jackson 把毫秒值误当成秒值。
         *
         * @param parser JSON 解析器
         * @param context 反序列化上下文
         * @return 解析后的 OffsetDateTime
         * @throws IOException 解析失败时抛出 IO 异常
         */
        @Override
        public OffsetDateTime deserialize(JsonParser parser, DeserializationContext context) throws IOException {
            return deserializeEpochMillisAware(
                parser,
                context,
                RelationMappingObjectMapperFactory::toOffsetDateTime,
                RelationMappingObjectMapperFactory::tryParseOffsetDateTime,
                InstantDeserializer.OFFSET_DATE_TIME::deserialize
            );
        }
    }

    /**
     * 将 epoch 毫秒时间戳稳定转换为 ZonedDateTime，并按应用时区规则解释本地时间。
     */
    private static final class EpochMillisZonedDateTimeDeserializer extends StdDeserializer<ZonedDateTime> {

        /**
         * 固定声明当前反序列化器负责 ZonedDateTime 类型。
         */
        private EpochMillisZonedDateTimeDeserializer() {
            super(ZonedDateTime.class);
        }

        /**
         * 允许 ZonedDateTime 接收 epoch 毫秒数值，避免 Jackson 把毫秒值误当成秒值。
         *
         * @param parser JSON 解析器
         * @param context 反序列化上下文
         * @return 解析后的 ZonedDateTime
         * @throws IOException 解析失败时抛出 IO 异常
         */
        @Override
        public ZonedDateTime deserialize(JsonParser parser, DeserializationContext context) throws IOException {
            return deserializeEpochMillisAware(
                parser,
                context,
                RelationMappingObjectMapperFactory::toZonedDateTime,
                RelationMappingObjectMapperFactory::tryParseZonedDateTime,
                InstantDeserializer.ZONED_DATE_TIME::deserialize
            );
        }
    }

    /**
     * 将 epoch 毫秒时间戳稳定转换为 OffsetTime，并按应用时区规则推导偏移量。
     */
    private static final class EpochMillisOffsetTimeDeserializer extends StdDeserializer<OffsetTime> {

        /**
         * 固定声明当前反序列化器负责 OffsetTime 类型。
         */
        private EpochMillisOffsetTimeDeserializer() {
            super(OffsetTime.class);
        }

        /**
         * 允许 OffsetTime 接收 epoch 毫秒数值，并稳定提取本地时间与偏移量。
         *
         * @param parser JSON 解析器
         * @param context 反序列化上下文
         * @return 解析后的 OffsetTime
         * @throws IOException 解析失败时抛出 IO 异常
         */
        @Override
        public OffsetTime deserialize(JsonParser parser, DeserializationContext context) throws IOException {
            return deserializeEpochMillisAware(
                parser,
                context,
                (epochMillis, ctx) -> toOffsetDateTime(epochMillis, ctx).toOffsetTime(),
                RelationMappingObjectMapperFactory::tryParseOffsetTime,
                OffsetTimeDeserializer.INSTANCE::deserialize
            );
        }
    }
}
