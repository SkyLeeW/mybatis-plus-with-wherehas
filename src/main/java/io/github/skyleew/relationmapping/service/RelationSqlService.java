package io.github.skyleew.relationmapping.service;

import cn.hutool.core.util.StrUtil;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.core.conditions.AbstractWrapper;
import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.baomidou.mybatisplus.core.metadata.TableFieldInfo;
import com.baomidou.mybatisplus.core.metadata.TableInfo;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.skyleew.relationmapping.domain.RelationModelStructure;
import io.github.skyleew.relationmapping.domain.SubSqlResult;
import io.github.skyleew.relationmapping.mapper.RawSqlMapper;
import io.github.skyleew.relationmapping.utils.LogicDeleteHelper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.lang.reflect.Field;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.Type;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;
import java.util.regex.Matcher;
import java.util.regex.Pattern;


/**
 * 用于组装并执行关联查询所需的原生 SQL 与结果映射逻辑。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class RelationSqlService {
    /**
     * 匹配 Wrapper 生成的命名参数占位符，便于转换为 apply 的顺序参数。
     */
    private static final Pattern WRAPPER_PARAMETER_PATTERN =
        Pattern.compile("#\\{\\s*(?:ew\\.)?paramNameValuePairs\\.([A-Za-z0-9_]+)\\s*\\}");

    /**
     * 缓存实体类的字段定义，避免结果格式化阶段重复反射查找。
     */
    private static final Map<Class<?>, Map<String, Field>> FIELD_CACHE = new ConcurrentHashMap<>();

    /**
     * 缓存实体类的列名到属性名映射，减少热路径上的字符串匹配成本。
     */
    private static final Map<Class<?>, Map<String, String>> COLUMN_FIELD_CACHE = new ConcurrentHashMap<>();

    private final ObjectMapper objectMapper;
    private final RawSqlMapper rawSqlMapper;
    private static final String SQL_TEMPLATE  = "select {} from {} where {} in  {}";

    private static final String BASE_SQL_TEMPLATE = "select {} from {} ";

    /**
     * 查询当前关联字段对应的全部子表数据，并把结果转换为实体字段名。
     *
     * @param fieldMetadata 当前关联字段的关系元数据
     * @return 返回已完成字段映射的子表结果集合
     */
    public List<Object> getResult(RelationModelStructure fieldMetadata) {

        String targetTable = fieldMetadata.getRelationTableStructure().getTableInfo().getTableName();
        String pk = fieldMetadata.getRelationTableStructure().getRelationFieldKey();
        String ids = "(" + fieldMetadata.getSelfTableStructure().getIds()+")";
        String sql = StrUtil.format(SQL_TEMPLATE,fieldMetadata.getRelationTableStructure().getTableInfo().getAllSqlSelect(),targetTable,pk,ids);

        // 若子表实体存在 @TableLogic 注解，则追加“未删除”条件。
        String notDeletedPred1 = LogicDeleteHelper
            .buildNotDeletedPredicate(
                fieldMetadata.getRelationTableStructure().getReflectClass(),
                fieldMetadata.getRelationTableStructure().getTableInfo()
            )
            .orElse(null);
        if (StrUtil.isNotBlank(notDeletedPred1)) {
            sql = appendWhereCondition(sql, notDeletedPred1);
        }
        HashMap<String,Object> execute = new HashMap<>();
        execute.put("sql",sql);
        if (log.isDebugEnabled()) {
            log.debug("[RelationSqlService] Relation SQL: {}", sql);
        }
        List<Object> raw = executeRawSQL(execute);
        debugFirstRowKeys(raw, "relation");
        debugColumnPresence(raw, Arrays.asList("spec_arr", "sku"), "relation");
        return formatV2(fieldMetadata, raw);

    }

    /**
     * 预加载子表（with）时支持按子表条件过滤，但不筛选父表。
     * 将 where 条件安全拼装为标准查询结构，并在主键集合约束后追加逻辑删除条件与子表过滤条件。
     *
     * @param fieldMetadata 当前关联字段的元数据，包含子表、外键和主表 id 集合信息
     * @param childWrapper 子表过滤条件包装器，仅影响预加载子表数据
     * @return 返回经过条件过滤后的关联结果集合
     */
    public List<Object> getResultWithWhere(RelationModelStructure fieldMetadata, Wrapper<?> childWrapper) {
        String targetTable = fieldMetadata.getRelationTableStructure().getTableInfo().getTableName();
        String targetField = fieldMetadata.getRelationTableStructure().getRelationFieldKey();
        String ids = fieldMetadata.getSelfTableStructure().getIds();
        if (StrUtil.isBlank(ids)) {
            return Collections.emptyList();
        }

        String sqlTemplate = StrUtil.format(BASE_SQL_TEMPLATE,
            fieldMetadata.getRelationTableStructure().getTableInfo().getAllSqlSelect(),
            targetTable
        );

        // 基础 WHERE（子表外键在主表 id 集中）。
        String base = targetField + " IN (" + ids + ")";

        // 逻辑删除条件。
        String notDeleted = LogicDeleteHelper
            .buildNotDeletedPredicate(
                fieldMetadata.getRelationTableStructure().getReflectClass(),
                fieldMetadata.getRelationTableStructure().getTableInfo()
            )
            .orElse(null);

        // 子表条件（QueryWrapper 产出的 SQL 片段）。
        String extra = childWrapper == null ? null : childWrapper.getCustomSqlSegment();
        String merged = " WHERE " + base;
        if (StrUtil.isNotBlank(notDeleted)) {
            merged += " AND " + notDeleted;
        }
        if (StrUtil.isNotBlank(extra)) {
            String trimmed = extra.trim();
            String lower = trimmed.toLowerCase();
            if (lower.startsWith("where ")) {
                merged += " AND " + trimmed.substring(6).trim();
            } else if (lower.startsWith("and ") || lower.startsWith("or ")) {
                merged += " " + trimmed;
            } else {
                merged += " AND " + trimmed;
            }
        }

        HashMap<String,Object> execute = new HashMap<>();
        execute.put("sqlTemplate", sqlTemplate);
        execute.put("conditions", merged);

        putWrapperParameters(execute, childWrapper);

        if (log.isDebugEnabled()) {
            log.debug("[RelationSqlService] With-Where SQL: {}{}", sqlTemplate, merged);
        }

        List<Object> raw = executeBindRawSQL(execute);
        debugFirstRowKeys(raw, "withWhere");
        debugColumnPresence(raw, Arrays.asList("spec_arr", "sku"), "withWhere");
        return formatV2(fieldMetadata, raw);
    }

    /**
     * 追加查询条件：
     * - 若原 SQL 已包含 WHERE，则拼接 AND 条件
     * - 若原 SQL 不包含 WHERE，则补齐 WHERE 再拼接条件
     */
    private String appendWhereCondition(String sql, String predicate) {
        if (StrUtil.isBlank(predicate)) {
            return sql;
        }
        String lower = sql.toLowerCase();
        if (lower.contains(" where ")) {
            return sql + " AND " + predicate;
        }
        return sql + " WHERE " + predicate;
    }

    /**
     * 统一完成列名映射、布尔值转换与 JSON 字段反序列化。
     */
    private List<Object> formatV2(RelationModelStructure modelStructure, List<Object> list) {
        if (list.isEmpty()) {
            return list;
        }
        Class<?> clazz = modelStructure.getRelationTableStructure().getReflectClass();
        Map<String, String> cachedColumnFieldMap = getColumnFieldMap(clazz);
        Map<String, Field> cachedFields = getFieldMap(clazz);

        List<Object> result = new ArrayList<>();
        for (Object rawItem : list) {
            HashMap<?, ?> rawMap = (HashMap<?, ?>) rawItem;
            HashMap<String, Object> processedMap = new HashMap<>();

            for (Object dbColumnKey : rawMap.keySet()) {
                String dbColumnName = StrUtil.toString(dbColumnKey);
                String javaFieldName = resolveFieldName(dbColumnName, cachedColumnFieldMap, cachedFields);
                Object rawValue = rawMap.get(dbColumnName);

                String candidateFieldName = javaFieldName != null ? javaFieldName : dbColumnName;
                Field targetField = cachedFields.get(candidateFieldName);

                // 先做 tinyint(1) 到数值/字符串字段的精确类型转换。
                if (targetField != null && rawValue instanceof Boolean b) {
                    Object coerced = coerceBooleanForTarget(b, targetField.getType());
                    if (coerced != null) {
                        processedMap.put(candidateFieldName, coerced);
                        continue;
                    }
                }

                String jsonLikeString = extractJsonLikeString(rawValue);
                if (targetField != null && jsonLikeString != null) {
                    try {
                        Object convertedValue = tryDeserializeJson(targetField, rawValue, jsonLikeString);
                        processedMap.put(candidateFieldName, convertedValue);
                    } catch (JsonProcessingException e) {
                        log.warn("字段 '{}' 的 JSON 反序列化失败，保留原始值: {}", candidateFieldName, e.getMessage());
                        processedMap.put(candidateFieldName, rawValue);
                    } catch (Exception e) {
                        log.error("字段 '{}' 的结果转换失败，保留原始值: {}", candidateFieldName, e.getMessage());
                        processedMap.put(candidateFieldName, rawValue);
                    }
                } else {
                    processedMap.put(candidateFieldName, rawValue);
                }
            }

            // 补齐缺失字段为 null：根据 TableInfo 列清单确保实体字段键存在
            try {
                TableInfo tinfo = modelStructure.getRelationTableStructure().getTableInfo();
                if (tinfo != null) {
                    // 主键属性
                    String keyProp = tinfo.getKeyProperty();
                    if (keyProp != null && !keyProp.isEmpty() && !processedMap.containsKey(keyProp)) {
                        processedMap.put(keyProp, null);
                    }
                    // 其他字段属性
                    for (TableFieldInfo fi : tinfo.getFieldList()) {
                        String prop = fi.getProperty();
                        if (prop != null && !processedMap.containsKey(prop)) {
                            processedMap.put(prop, null);
                        }
                    }
                }
            } catch (Exception ignore) { }
            result.add(processedMap);
        }
        return result;
    }

    /**
     * 从缓存中读取实体字段定义，不存在时再初始化一次。
     */
    private Map<String, Field> getFieldMap(Class<?> clazz) {
        return FIELD_CACHE.computeIfAbsent(clazz, targetClass -> {
            Map<String, Field> fieldMap = new HashMap<>();
            for (Field field : targetClass.getDeclaredFields()) {
                fieldMap.put(field.getName(), field);
            }
            return fieldMap;
        });
    }

    /**
     * 从缓存中读取列名到属性名映射，避免每次都遍历全部字段。
     */
    private Map<String, String> getColumnFieldMap(Class<?> clazz) {
        return COLUMN_FIELD_CACHE.computeIfAbsent(clazz, targetClass -> {
            Map<String, String> columnFieldMap = new HashMap<>();
            for (Field field : targetClass.getDeclaredFields()) {
                columnFieldMap.put(field.getName(), field.getName());
                columnFieldMap.put(StrUtil.toUnderlineCase(field.getName()), field.getName());
                TableField tableField = field.getAnnotation(TableField.class);
                if (tableField != null && StrUtil.isNotBlank(tableField.value())) {
                    columnFieldMap.put(tableField.value(), field.getName());
                }
            }
            return columnFieldMap;
        });
    }

    /**
     * 按列名、属性名与驼峰推导顺序解析目标字段名。
     */
    private String resolveFieldName(String dbColumnName,
                                    Map<String, String> columnFieldMap,
                                    Map<String, Field> fieldMap) {
        String fieldName = columnFieldMap.get(dbColumnName);
        if (fieldName != null) {
            return fieldName;
        }
        String camelName = StrUtil.toCamelCase(dbColumnName);
        if (fieldMap.containsKey(camelName)) {
            return camelName;
        }
        return null;
    }

    /**
     * 把 JDBC 返回值统一提取为可判定是否为 JSON 的字符串表示。
     */
    private String extractJsonLikeString(Object rawValue) {
        if (rawValue instanceof String stringValue) {
            return stringValue;
        }
        if (rawValue == null) {
            return null;
        }
        try {
            if (rawValue instanceof byte[] bytes) {
                return new String(bytes, java.nio.charset.StandardCharsets.UTF_8);
            }
            if (rawValue instanceof java.sql.Clob clob) {
                java.io.Reader reader = clob.getCharacterStream();
                StringBuilder stringBuilder = new StringBuilder();
                char[] buffer = new char[1024];
                int length;
                while ((length = reader.read(buffer)) != -1) {
                    stringBuilder.append(buffer, 0, length);
                }
                return stringBuilder.toString();
            }
            if (rawValue instanceof java.sql.Blob blob) {
                byte[] bytes = blob.getBytes(1L, (int) blob.length());
                return new String(bytes, java.nio.charset.StandardCharsets.UTF_8);
            }
            if ("org.postgresql.util.PGobject".equals(rawValue.getClass().getName())) {
                java.lang.reflect.Method method = rawValue.getClass().getMethod("getValue");
                Object value = method.invoke(rawValue);
                return value == null ? null : value.toString();
            }
        } catch (Exception ignore) { }
        return null;
    }

    /**
     * 仅在内容确实是 JSON 时按目标字段类型做精确反序列化。
     */
    private Object tryDeserializeJson(Field targetField, Object rawValue, String jsonLikeString) throws Exception {
        String normalizedJson = normalizeJsonString(jsonLikeString);
        if (normalizedJson == null) {
            return rawValue;
        }
        Class<?> targetFieldType = targetField.getType();
        if (List.class.isAssignableFrom(targetFieldType)) {
            Type genericType = targetField.getGenericType();
            if (genericType instanceof ParameterizedType parameterizedType) {
                Type listContentType = parameterizedType.getActualTypeArguments()[0];
                return objectMapper.readValue(
                    normalizedJson,
                    objectMapper.getTypeFactory().constructCollectionType(
                        List.class,
                        objectMapper.getTypeFactory().constructType(listContentType)
                    )
                );
            }
            return objectMapper.readValue(
                normalizedJson,
                objectMapper.getTypeFactory().constructCollectionType(List.class, Object.class)
            );
        }
        if (!targetFieldType.isPrimitive()
            && !targetFieldType.isArray()
            && !targetFieldType.isEnum()
            && !Collection.class.isAssignableFrom(targetFieldType)
            && !Map.class.isAssignableFrom(targetFieldType)
            && !String.class.equals(targetFieldType)) {
            return objectMapper.readValue(normalizedJson, targetFieldType);
        }
        return rawValue;
    }

    /**
     * 统一识别标准 JSON 与双层包裹 JSON，非 JSON 内容直接返回空值。
     */
    private String normalizeJsonString(String jsonLikeString) {
        String trimmedValue = jsonLikeString == null ? null : jsonLikeString.trim();
        if (StrUtil.isBlank(trimmedValue)) {
            return null;
        }
        if (trimmedValue.startsWith("[") || trimmedValue.startsWith("{")) {
            return trimmedValue;
        }
        if (trimmedValue.startsWith("\"") && trimmedValue.endsWith("\"") && trimmedValue.length() >= 2) {
            String innerValue = trimmedValue.substring(1, trimmedValue.length() - 1).trim();
            if (innerValue.startsWith("[") || innerValue.startsWith("{")) {
                return innerValue;
            }
        }
        return null;
    }

    /**
     * tinyint(1) 通过 JDBC 可能被映射为 Boolean。若目标字段是数值或字符串，做 0/1 兼容转换。
     * 返回 null 表示不处理，交由默认逻辑。
     */
    private Object coerceBooleanForTarget(Boolean b, Class<?> targetType) {
        if (targetType == null) return null;
        if (targetType == long.class || targetType == Long.class) return b ? 1L : 0L;
        if (targetType == int.class || targetType == Integer.class) return b ? 1 : 0;
        if (targetType == short.class || targetType == Short.class) return (short) (b ? 1 : 0);
        if (targetType == byte.class || targetType == Byte.class) return (byte) (b ? 1 : 0);
        if (targetType == double.class || targetType == Double.class) return b ? 1d : 0d;
        if (targetType == float.class || targetType == Float.class) return b ? 1f : 0f;
        if (java.math.BigDecimal.class.isAssignableFrom(targetType)) return new java.math.BigDecimal(b ? 1 : 0);
        if (java.math.BigInteger.class.isAssignableFrom(targetType)) return java.math.BigInteger.valueOf(b ? 1 : 0);
        if (targetType == String.class) return b ? "1" : "0";
        return null;
    }
    public SubSqlResult getSubSelectResult(RelationModelStructure fieldMetadata, Wrapper<?> queryWrapper) {
        SubSqlResult subSqlResult = new SubSqlResult();
        String targetTable = fieldMetadata.getRelationTableStructure().getTableInfo().getTableName();

        // 获取安全的条件片段（包含占位符）
        String conditionSegment = queryWrapper.getCustomSqlSegment();

        if(StrUtil.isBlank(conditionSegment)){
            subSqlResult.setIsBlankWhereColumn(true);
            return  subSqlResult;
        }

        // 合并“未删除”条件到子查询条件片段
        String mergedConditions = conditionSegment;
        try {
            String pred = LogicDeleteHelper
                .buildNotDeletedPredicate(
                    fieldMetadata.getRelationTableStructure().getReflectClass(),
                    fieldMetadata.getRelationTableStructure().getTableInfo()
                )
                .orElse(null);
            if (StrUtil.isNotBlank(pred)) {
                if (StrUtil.isBlank(mergedConditions)) {
                    mergedConditions = " WHERE " + pred;
                } else {
                    // 若已存在 WHERE，则直接追加 AND 条件；否则补全 WHERE 再拼接
                    String low = mergedConditions.trim().toLowerCase();
                    if (low.startsWith("where ") || low.contains(" where ")) {
                        mergedConditions += " AND " + pred;
                    } else {
                        mergedConditions = " WHERE " + mergedConditions + " AND " + pred;
                    }
                }
            }
        } catch (Exception ignore) { }

        // 把 hasWhere 构造成 EXISTS 子查询，并精确解析主表列与子表外键列。
        String outerPropertyCandidate;
        if (fieldMetadata.getSelfTableStructure().getRelationField() != null) {
            outerPropertyCandidate = fieldMetadata.getSelfTableStructure().getRelationField().getName();
        } else {
            outerPropertyCandidate = StrUtil.toCamelCase(fieldMetadata.getSelfTableStructure().getRelationFieldKey());
        }
        String outerColumn = getColumnName(
            fieldMetadata.getSelfTableStructure().getTableInfo(),
            outerPropertyCandidate
        );
        if (outerColumn == null){
            log.error("关联目标的字段名称找不到,hasWhere失效");
            subSqlResult.setIsBlankWhereColumn(true);
            return  subSqlResult;
        }
        String innerFkColumn = fieldMetadata.getRelationTableStructure().getRelationFieldKey();

        // 把 Wrapper 命名参数转换为 apply 顺序参数，避免把值直接内联进 SQL。
        SubSqlResult convertedCondition = new SubSqlResult();
        convertedCondition.setSql(mergedConditions);
        if (queryWrapper instanceof AbstractWrapper) {
            AbstractWrapper<?, ?, ?> abstractWrapper = (AbstractWrapper<?, ?, ?>) queryWrapper;
            convertedCondition = convertWrapperSqlToApplySql(mergedConditions, abstractWrapper.getParamNameValuePairs());
        }

        String condNoWhere = stripLeadingWhere(convertedCondition.getSql());
        String outerTableName = fieldMetadata.getSelfTableStructure().getTableInfo().getTableName();
        String alias = "rel1";

        StringBuilder exists = new StringBuilder();
        exists.append("EXISTS (SELECT 1 FROM ")
            .append(targetTable).append(" ").append(alias)
            .append(" WHERE ")
            .append(alias).append(".").append(innerFkColumn)
            .append(" = ")
            .append(outerTableName).append(".").append(outerColumn);
        if (StrUtil.isNotBlank(condNoWhere)) {
            exists.append(" AND ").append(condNoWhere.trim());
        }
        exists.append(")");
        if (log.isDebugEnabled()) {
            log.debug("[RelationSqlService] whereHas EXISTS: {}", exists);
        }
        subSqlResult.setSql(exists.toString());
        subSqlResult.setParameters(convertedCondition.getParameters());
        return subSqlResult;

    }

    /**
     * 获取关联模型的数量统计结果
     * @param fieldMetadata 关系模型元数据
     * @return 返回一个 Map，键是关联外键，值是对应的数量
     */
    public Map<String, Long> getCountResult(RelationModelStructure fieldMetadata) {
        String targetTable = fieldMetadata.getRelationTableStructure().getTableInfo().getTableName();
        String targetField = fieldMetadata.getRelationTableStructure().getRelationFieldKey(); // 目标表的关联字段
        String ids = fieldMetadata.getSelfTableStructure().getIds();

        if (ids == null || ids.isEmpty()) {
            return new HashMap<>();
        }

        String whereClause = targetField + " IN (" + ids + ")";
        String notDeletedPred2 = LogicDeleteHelper
            .buildNotDeletedPredicate(
                fieldMetadata.getRelationTableStructure().getReflectClass(),
                fieldMetadata.getRelationTableStructure().getTableInfo()
            )
            .orElse(null);
        if (StrUtil.isNotBlank(notDeletedPred2)) {
            whereClause += " AND " + notDeletedPred2;
        }
        String sql = "SELECT " + targetField
            + ", COUNT(*) as relation_count FROM " + targetTable
            + " WHERE " + whereClause
            + " GROUP BY " + targetField;

        HashMap<String, Object> execute = new HashMap<>();
        execute.put("sql", sql);

        List<Object> rawResult = executeRawSQL(execute);

        // 将 List<HashMap> 转换为 Map<String, Long> 以方便处理
        return rawResult.stream()
            .map(item -> (HashMap<String, Object>) item)
            .collect(Collectors.toMap(
                map -> String.valueOf(map.get(targetField)),
                map -> ((Number) map.get("relation_count")).longValue()
            ));
    }


    /**
     * 根据表元数据和输入名称解析对应的数据库列名。
     *
     * @param tableInfo MyBatis-Plus 维护的数据表元信息
     * @param inputName 可能是实体属性名或数据库列名的输入值
     * @return 返回解析后的数据库列名，若无法解析则返回 null
     */
    public static String getColumnName(TableInfo tableInfo, String inputName) {
        if (tableInfo == null || inputName == null || inputName.isEmpty()) {
            return null;
        }

        // 1) 先按属性名匹配（包含主键与普通字段）。
        String keyProp = tableInfo.getKeyProperty();
        if (keyProp != null && keyProp.equals(inputName)) {
            return tableInfo.getKeyColumn();
        }
        Optional<TableFieldInfo> byProperty = tableInfo.getFieldList().stream()
            .filter(f -> inputName.equals(f.getProperty()))
            .findFirst();
        if (byProperty.isPresent()) {
            return byProperty.get().getColumn();
        }

        // 2) 如果传入的本身就是列名（snake_case），直接返回。
        String keyCol = tableInfo.getKeyColumn();
        if (keyCol != null && keyCol.equals(inputName)) {
            return inputName;
        }
        Optional<TableFieldInfo> byColumn = tableInfo.getFieldList().stream()
            .filter(f -> inputName.equals(f.getColumn()))
            .findFirst();
        if (byColumn.isPresent()) {
            return inputName;
        }

        // 3) 驼峰转下划线后再次匹配，保证属性名与列名都可解析。
        String underline = StrUtil.toUnderlineCase(inputName);
        if (underline != null && !underline.equals(inputName)) {
            if (underline.equals(keyCol)) {
                return underline;
            }
            boolean exists = tableInfo.getFieldList().stream()
                .anyMatch(f -> underline.equals(f.getColumn()));
            if (exists) {
                return underline;
            }
        }

        return null;
    }

    /**
     * 执行原生SQL
     * @param sql  sql语句
     * @return 返回结果
     */
    public List<Object> executeRawSQL(HashMap<String,Object> sql) {
        return rawSqlMapper.executeRawSQL(sql);
    }

    /**
     * 执行带绑定参数的原生 SQL。
     *
     * @param sql SQL 模板与参数容器
     * @return 返回查询结果
     */
    public List<Object> executeBindRawSQL(HashMap<String,Object> sql) {
        return rawSqlMapper.executeBindRawSQL(sql);
    }

    /**
     * 打印首行的所有列键，方便排查字段是否返回
     */
    private void debugFirstRowKeys(List<Object> list, String tag) {
        if (!log.isDebugEnabled()) return;
        try {
            if (list == null || list.isEmpty()) {
                log.debug("[RelationSqlService] {} result: empty", tag);
                return;
            }
            Object first = list.get(0);
            if (first instanceof Map) {
                Map<?, ?> m = (Map<?, ?>) first;
                log.debug("[RelationSqlService] {} first row keys: {}", tag, m.keySet());
            } else {
                log.debug("[RelationSqlService] {} first row type: {}", tag, first.getClass().getName());
            }
        } catch (Exception ignore) { }
    }

    /**
     * 统计指定列在结果集中出现（且非 null）的次数，并打印全量键名并集。
     */
    private void debugColumnPresence(List<Object> list, List<String> columns, String tag) {
        if (!log.isDebugEnabled()) return;
        try {
            if (list == null || list.isEmpty()) return;
            Set<Object> unionKeys = new LinkedHashSet<>();
            Map<String, Integer> nonNullCount = new LinkedHashMap<>();
            for (String c : columns) nonNullCount.put(c, 0);

            for (Object row : list) {
                if (!(row instanceof Map)) continue;
                Map<?,?> m = (Map<?,?>) row;
                unionKeys.addAll(m.keySet());
                for (String c : columns) {
                    Object v = m.get(c);
                    if (v != null) {
                        nonNullCount.put(c, nonNullCount.get(c) + 1);
                    }
                }
            }
            log.debug("[RelationSqlService] {} union keys: {}", tag, unionKeys);
            log.debug("[RelationSqlService] {} non-null counts: {}", tag, nonNullCount);
        } catch (Exception ignore) { }
    }

    /**
     * 去掉开头的 WHERE 前缀，便于拼接到 EXISTS 主体条件后。
     */
    private static String stripLeadingWhere(String sql) {
        if (StrUtil.isBlank(sql)) return sql;
        String s = sql.trim();
        String lower = s.toLowerCase(Locale.ROOT);
        if (lower.startsWith("where ")) {
            return s.substring(6).trim();
        }
        return s;
    }

    /**
     * 把 Wrapper 的命名参数占位符改写为 apply 可识别的顺序占位符。
     */
    private SubSqlResult convertWrapperSqlToApplySql(String sql, Map<String, Object> params) {
        SubSqlResult subSqlResult = new SubSqlResult();
        if (StrUtil.isBlank(sql)) {
            subSqlResult.setSql(sql);
            return subSqlResult;
        }
        Matcher matcher = WRAPPER_PARAMETER_PATTERN.matcher(sql);
        StringBuffer sb = new StringBuffer();
        int parameterIndex = 0;
        while (matcher.find()) {
            String key = matcher.group(1);
            if (params == null || !params.containsKey(key)) {
                throw new IllegalArgumentException("未找到关联条件所需参数: " + key);
            }
            subSqlResult.getParameters().add(params.get(key));
            matcher.appendReplacement(sb, Matcher.quoteReplacement("{" + parameterIndex + "}"));
            parameterIndex++;
        }
        matcher.appendTail(sb);
        subSqlResult.setSql(sb.toString());
        return subSqlResult;
    }

    /**
     * 把 Wrapper 参数按 MyBatis 期望的 ew 结构放入执行上下文。
     */
    private void putWrapperParameters(HashMap<String, Object> execute, Wrapper<?> wrapper) {
        if (!(wrapper instanceof AbstractWrapper<?, ?, ?> abstractWrapper)) {
            return;
        }
        HashMap<String, Object> ew = new HashMap<>();
        ew.put("paramNameValuePairs", abstractWrapper.getParamNameValuePairs());
        execute.put("ew", ew);
    }

}



