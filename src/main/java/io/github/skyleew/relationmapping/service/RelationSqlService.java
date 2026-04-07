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
import java.util.*;
import java.util.stream.Collectors;
import java.util.regex.Matcher;
import java.util.regex.Pattern;


/**
 * sql查询
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class RelationSqlService {
    private final ObjectMapper objectMapper;
    private final RawSqlMapper rawSqlMapper;
    String SQL_TEMPLATE  = "select {} from {} where {} in  {}";

    String BASE_SQL_TEMPLATE = "select {} from {} ";

    String COUNT_SQL_TEMPLATE  = "SELECT {}, COUNT(*) as relation_count FROM {} WHERE {} IN ({}) GROUP BY {}";

//    RelationSqlService() {
//        SqlSessionFactory sqlSessionFactory = SpringUtil.getBean(SqlSessionFactory.class) ;
//        this.sqlSession = sqlSessionFactory.openSession();
//    }

    /**
     * 得到结果
     * @param fieldMetadata 获取到值
     */
    public List<Object> getResult(RelationModelStructure fieldMetadata) {

        String targetTable = fieldMetadata.getRelationTableStructure().getTableInfo().getTableName();
        String pk = fieldMetadata.getRelationTableStructure().getRelationFieldKey();
        String ids = "(" + fieldMetadata.getSelfTableStructure().getIds()+")";
        String sql = StrUtil.format(SQL_TEMPLATE,fieldMetadata.getRelationTableStructure().getTableInfo().getAllSqlSelect(),targetTable,pk,ids);

        // 若子表实体存在 @TableLogic 注解，则追加“未删除”条件
        String notDeletedPred1 = LogicDeleteHelper
            .buildNotDeletedPredicate(
                fieldMetadata.getRelationTableStructure().getReflectClass(),
                fieldMetadata.getRelationTableStructure().getTableInfo()
            )
            .orElse(null);
        if (StrUtil.isNotBlank(notDeletedPred1)) {
            // 更稳健：若原SQL无 WHERE，则自动补齐 WHERE 再拼接 AND 条件
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
     * 将 where 条件安全拼装为：
     *   SELECT <列> FROM <子表>
     *   WHERE <targetField> IN (ids)
     *     [AND 未删除条件]
     *     [AND <子表条件>]
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

        // 基础 WHERE（子表外键在主表 id 集中）
        String base = targetField + " IN (" + ids + ")";

        // 逻辑删除条件
        String notDeleted = LogicDeleteHelper
            .buildNotDeletedPredicate(
                fieldMetadata.getRelationTableStructure().getReflectClass(),
                fieldMetadata.getRelationTableStructure().getTableInfo()
            )
            .orElse(null);

        // 子表条件（QueryWrapper 产出的 SQL 片段）
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

        if (childWrapper instanceof AbstractWrapper) {
            AbstractWrapper<?, ?, ?> abstractWrapper = (AbstractWrapper<?, ?, ?>) childWrapper;
            execute.put("ew", new HashMap<String, Object>(){{
                put("paramNameValuePairs", abstractWrapper.getParamNameValuePairs());
            }});
        }

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
     * 更健壮的结果格式化，支持列别名直达、JSON 标准化解析
     */
    private List<Object> formatV2(RelationModelStructure modelStructure, List<Object> list) {
        if (list.isEmpty()) {
            return list;
        }
        Class<?> clazz = modelStructure.getRelationTableStructure().getReflectClass();
        Object firstItem = list.get(0);
        HashMap<?, ?> mapKeys = (HashMap<?, ?>) firstItem;
        HashMap<String, String> columnToFieldNameMap = new HashMap<>();

        // 列名 -> 字段名映射，尽可能多地命中
        for (Object columnKey : mapKeys.keySet()) {
            String dbColumnName = StrUtil.toString(columnKey);
            for (Field field : clazz.getDeclaredFields()) {
                TableField tf = field.getAnnotation(TableField.class);
                if (tf != null && tf.value().equals(dbColumnName)) {
                    columnToFieldNameMap.put(dbColumnName, field.getName());
                    break;
                }
                if (dbColumnName.equals(field.getName())) { // 别名=属性名
                    columnToFieldNameMap.put(dbColumnName, field.getName());
                    break;
                }
                if (StrUtil.toCamelCase(dbColumnName).equals(field.getName())) { // 下划线->驼峰
                    columnToFieldNameMap.put(dbColumnName, field.getName());
                    break;
                }
            }
        }

        List<Object> result = new ArrayList<>();
            for (Object rawItem : list) {
                HashMap<?, ?> rawMap = (HashMap<?, ?>) rawItem;
                HashMap<String, Object> processedMap = new HashMap<>();

            for (Object dbColumnKey : rawMap.keySet()) {
                String dbColumnName = StrUtil.toString(dbColumnKey);
                String javaFieldName = columnToFieldNameMap.get(dbColumnName);
                Object rawValue = rawMap.get(dbColumnName);

                String candidateFieldName = javaFieldName != null ? javaFieldName : dbColumnName;
                // 兜底：如果首行未包含该列导致未建立映射，则按驼峰尝试匹配实体字段
                if (javaFieldName == null) {
                    String camel = StrUtil.toCamelCase(dbColumnName);
                    try {
                        if (camel != null && !camel.equals(dbColumnName)) {
                            clazz.getDeclaredField(camel);
                            candidateFieldName = camel; // 存在同名字段则使用字段名
                        }
                    } catch (NoSuchFieldException ignore) { }
                }

                // 先做 tinyint(1)->boolean 的兼容：当返回值是 Boolean，但目标字段是数值/字符串时，转换为 0/1
                try {
                    Field targetField = clazz.getDeclaredField(candidateFieldName);
                    if (rawValue instanceof Boolean b) {
                        Object coerced = coerceBooleanForTarget(b, targetField.getType());
                        if (coerced != null) {
                            processedMap.put(candidateFieldName, coerced);
                            continue; // 已处理
                        }
                    }
                } catch (NoSuchFieldException ignore) { }

                String jsonLikeString = null;
                if (rawValue instanceof String s) {
                    jsonLikeString = s;
                } else if (rawValue != null) {
                    try {
                        if (rawValue instanceof byte[] bytes) {
                            jsonLikeString = new String(bytes, java.nio.charset.StandardCharsets.UTF_8);
                        } else if (rawValue instanceof java.sql.Clob clob) {
                            java.io.Reader reader = clob.getCharacterStream();
                            StringBuilder sb = new StringBuilder();
                            char[] buf = new char[1024];
                            int len;
                            while ((len = reader.read(buf)) != -1) { sb.append(buf, 0, len); }
                            jsonLikeString = sb.toString();
                        } else if (rawValue instanceof java.sql.Blob blob) {
                            byte[] bytes = blob.getBytes(1L, (int) blob.length());
                            jsonLikeString = new String(bytes, java.nio.charset.StandardCharsets.UTF_8);
                        } else if ("org.postgresql.util.PGobject".equals(rawValue.getClass().getName())) {
                            java.lang.reflect.Method m = rawValue.getClass().getMethod("getValue");
                            Object v = m.invoke(rawValue);
                            if (v != null) { jsonLikeString = v.toString(); }
                        }
                    } catch (Exception ignore) { }
                }

                if (candidateFieldName != null && jsonLikeString != null) {
                    try {
                        String stringValue = jsonLikeString;
                        String sv = stringValue == null ? null : stringValue.trim();
                        boolean looksJson = StrUtil.isNotBlank(sv) && (sv.startsWith("[") || sv.startsWith("{"));
                        // 兼容双重包裹的 JSON：例如 '"[1,2]"' 或 '"{...}"'
                        if (!looksJson && StrUtil.isNotBlank(sv) && sv.startsWith("\"") && sv.endsWith("\"") && sv.length() >= 2) {
                            String inner = sv.substring(1, sv.length() - 1).trim();
                            if (inner.startsWith("[") || inner.startsWith("{")) {
                                stringValue = inner;
                                looksJson = true;
                            }
                        }

                        if (looksJson) {
                            Field targetJavaField = clazz.getDeclaredField(candidateFieldName);
                            Class<?> targetFieldType = targetJavaField.getType();

                            if (List.class.isAssignableFrom(targetFieldType)) {
                                java.lang.reflect.Type genericType = targetJavaField.getGenericType();
                                if (genericType instanceof java.lang.reflect.ParameterizedType pType) {
                                    java.lang.reflect.Type listContentType = pType.getActualTypeArguments()[0];
                                    com.fasterxml.jackson.databind.JavaType jacksonType = objectMapper.getTypeFactory().constructType(listContentType);
                                    com.fasterxml.jackson.databind.JavaType collectionType = objectMapper.getTypeFactory().constructCollectionType(List.class, jacksonType);
                                    processedMap.put(candidateFieldName, objectMapper.readValue(stringValue, collectionType));
                                } else {
                                    processedMap.put(candidateFieldName, objectMapper.readValue(stringValue, objectMapper.getTypeFactory().constructCollectionType(List.class, Object.class)));
                                }
                            } else if (!targetFieldType.isPrimitive() && !targetFieldType.isArray() && !targetFieldType.isEnum() && !Collection.class.isAssignableFrom(targetFieldType) && !Map.class.isAssignableFrom(targetFieldType) && !String.class.equals(targetFieldType)) {
                                processedMap.put(candidateFieldName, objectMapper.readValue(stringValue, targetFieldType));
                            } else {
                                processedMap.put(candidateFieldName, rawValue);
                            }
                        } else {
                            processedMap.put(candidateFieldName, rawValue);
                        }
                    } catch (NoSuchFieldException e) {
                        log.debug("Field '{}' not found in target class {}. Original value kept.", candidateFieldName, clazz.getName());
                        processedMap.put(candidateFieldName, rawValue);
                    } catch (JsonProcessingException e) {
                        log.warn("Failed to deserialize JSON string for field '{}' in class {}. Value kept as string. Error: {}", candidateFieldName, clazz.getName(), e.getMessage());
                        processedMap.put(candidateFieldName, rawValue);
                    } catch (Exception e) {
                        log.error("Error processing field '{}' for class {}. Original value kept. Error: {}", candidateFieldName, clazz.getName(), e.getMessage());
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

        // 将 hasWhere 从 IN 模式改为 EXISTS 模式
        // 解析主表列与子表外键列
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

        // 将 Wrapper 参数占位符内联为字面量
        String conditionsForExists = mergedConditions;
        if (queryWrapper instanceof AbstractWrapper) {
            AbstractWrapper<?, ?, ?> abstractWrapper = (AbstractWrapper<?, ?, ?>) queryWrapper;
            Map<String, Object> params = abstractWrapper.getParamNameValuePairs();
            if (params != null && !params.isEmpty()) {
                conditionsForExists = inlineParams(conditionsForExists, params);
            }
        }

        String condNoWhere = stripLeadingWhere(conditionsForExists);
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
        return subSqlResult;

    }
    /**
     * 将数据的字段与实体类属性保持一致
     * @param modelStructure 模型结构体
     * @param list 通过sql返回的集合 (这里通常是 List<HashMap<String, Object>>)
     * @return
     */
    private List<Object> format(RelationModelStructure modelStructure, List<Object> list) {
        if (list.isEmpty()) {
            return list;
        }
        Class<?> clazz = modelStructure.getRelationTableStructure().getReflectClass();
        Object firstItem = list.get(0);
        HashMap<?, ?> mapKeys = (HashMap<?, ?>) firstItem;
        HashMap<String, String> columnToFieldNameMap = new HashMap<>();

        // 构建数据库列名到 Java 字段名的映射
        for (Object columnKey : mapKeys.keySet()) {
            String dbColumnName = StrUtil.toString(columnKey);
            for (Field field : clazz.getDeclaredFields()) {
                if (field.getAnnotation(TableField.class) != null) {
                    if (field.getAnnotation(TableField.class).value().equals(dbColumnName)) {
                        columnToFieldNameMap.put(dbColumnName, field.getName());
                        break;
                    }
                }
                if (dbColumnName.equals(field.getName())) {
                    columnToFieldNameMap.put(dbColumnName, field.getName());
                    break;
                }
                if (StrUtil.toCamelCase(dbColumnName).equals(field.getName())) {
                    columnToFieldNameMap.put(dbColumnName, field.getName());
                    break;
                }
            }
        }

        List<Object> result = new ArrayList<>();
        for (Object rawItem : list) {
            HashMap<?, ?> rawMap = (HashMap<?, ?>) rawItem;
            HashMap<String, Object> processedMap = new HashMap<>();

            for (Object dbColumnKey : rawMap.keySet()) {
                String dbColumnName = StrUtil.toString(dbColumnKey);
                String javaFieldName = columnToFieldNameMap.get(dbColumnName);
                Object rawValue = rawMap.get(dbColumnName);

                // tinyint(1) 兼容：若 JDBC 给出 Boolean，而实体字段是数值/字符串，则转换为 0/1
                if (javaFieldName != null && rawValue instanceof Boolean b) {
                    try {
                        Field targetJavaField = clazz.getDeclaredField(javaFieldName);
                        Object coerced = coerceBooleanForTarget(b, targetJavaField.getType());
                        if (coerced != null) {
                            processedMap.put(javaFieldName, coerced);
                            continue;
                        }
                    } catch (NoSuchFieldException ignore) { }
                }

                if (javaFieldName != null && rawValue instanceof String) {
                    try {
                        String stringValue = (String) rawValue;
                        // 只有当字符串看起来像JSON时才尝试解析
                        if (StrUtil.isNotBlank(stringValue) && (stringValue.trim().startsWith("[") || stringValue.trim().startsWith("{"))) {
                            Field targetJavaField = clazz.getDeclaredField(javaFieldName);
                            Class<?> targetFieldType = targetJavaField.getType();

                            if (List.class.isAssignableFrom(targetFieldType)) {
                                java.lang.reflect.Type genericType = targetJavaField.getGenericType();
                                if (genericType instanceof java.lang.reflect.ParameterizedType pType) {
                                    java.lang.reflect.Type listContentType = pType.getActualTypeArguments()[0];
                                    com.fasterxml.jackson.databind.JavaType jacksonType = objectMapper.getTypeFactory().constructType(listContentType);
                                    com.fasterxml.jackson.databind.JavaType collectionType = objectMapper.getTypeFactory().constructCollectionType(List.class, jacksonType);
                                    processedMap.put(javaFieldName, objectMapper.readValue(stringValue, collectionType));
                                } else {
                                    // 如果无法确定泛型，作为 List<Object> 或 List<Map> 处理
                                    processedMap.put(javaFieldName, objectMapper.readValue(stringValue, objectMapper.getTypeFactory().constructCollectionType(List.class, Object.class)));
                                }
                            } else if (!targetFieldType.isPrimitive() && !targetFieldType.isArray() && !targetFieldType.isEnum() && !Collection.class.isAssignableFrom(targetFieldType) && !Map.class.isAssignableFrom(targetFieldType) && !String.class.equals(targetFieldType)) {
                                processedMap.put(javaFieldName, objectMapper.readValue(stringValue, targetFieldType));
                            } else {
                                // 字段类型是基础类型但内容像JSON，保持原样
                                processedMap.put(javaFieldName, rawValue);
                            }
                        } else {
                            // 不是JSON格式的字符串，保持原样
                            processedMap.put(javaFieldName, rawValue);
                        }
                    } catch (NoSuchFieldException e) {
                        log.debug("Field '{}' not found in target class {}. Original value kept.", javaFieldName, clazz.getName());
                        processedMap.put(javaFieldName, rawValue);
                    } catch (JsonProcessingException e) {
                        log.warn("Failed to deserialize JSON string for field '{}' in class {}. Value kept as string. Error: {}", javaFieldName, clazz.getName(), e.getMessage());
                        processedMap.put(javaFieldName, rawValue);
                    } catch (Exception e) {
                        log.error("Error processing field '{}' for class {}. Original value kept. Error: {}", javaFieldName, clazz.getName(), e.getMessage());
                        processedMap.put(javaFieldName, rawValue);
                    }
                } else {
                    processedMap.put(javaFieldName != null ? javaFieldName : dbColumnName, rawValue);
                }
            }
            result.add(processedMap);
        }
        return result;
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

        String sql = StrUtil.format(COUNT_SQL_TEMPLATE, targetField, targetTable, targetField, ids, targetField);

        // 统计子表计数时追加“未删除”条件
        String notDeletedPred2 = LogicDeleteHelper
            .buildNotDeletedPredicate(
                fieldMetadata.getRelationTableStructure().getReflectClass(),
                fieldMetadata.getRelationTableStructure().getTableInfo()
            )
            .orElse(null);
        if (StrUtil.isNotBlank(notDeletedPred2)) {
            // 更稳健：若原SQL无 WHERE，则自动补齐 WHERE 再拼接 AND 条件
            sql = appendWhereCondition(sql, notDeletedPred2);
        }

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
     * 根据实体类的 Class 对象和属性名获取数据库字段名
     *
     * @param entityClass  实体类的 Class 对象
     * @param propertyName 实体类的成员变量名 (属性名)
     * @return 对应的数据库字段名，如果找不到则返回 null
     */
    public static String getColumnName(TableInfo tableInfo, String inputName) {
        if (tableInfo == null || inputName == null || inputName.isEmpty()) {
            return null;
        }

        // 1) 先按属性名匹配（包含主键与普通字段）
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

        // 2) 如果传入的本身就是列名（snake_case），直接返回
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

        // 3) 驼峰 -> 下划线 Fallback（尽力推断）
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

        // 找不到则返回 null，调用方自行处理
        return null;
    }

    /**
     * 执行原生SQL
     * @param sql  sql语句
     * @return 返回结果
     */
    public List<Object> executeRawSQL(HashMap<String,Object> sql) {
        return rawSqlMapper.executeRawSQL(sql);
    }    /**
     * 执行原生SQL
     * @param sql  sql语句
     * @return 返回结果
     */
    public List<Object> executeBindRawSQL(HashMap<String,Object> sql) {
        // 调用绑定参数版本的执行方法，使用 sqlTemplate + conditions + ew 进行安全拼装
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

    // ---------- helpers for EXISTS mode ----------
    private static String stripLeadingWhere(String sql) {
        if (StrUtil.isBlank(sql)) return sql;
        String s = sql.trim();
        String lower = s.toLowerCase(Locale.ROOT);
        if (lower.startsWith("where ")) {
            return s.substring(6).trim();
        }
        return s;
    }

    private static String inlineParams(String sql, Map<String, Object> params) {
        if (StrUtil.isBlank(sql) || params == null || params.isEmpty()) return sql;
        // 支持 #{ew.paramNameValuePairs.KEY} 或 #{paramNameValuePairs.KEY}
        Pattern p = Pattern.compile("#\\{\\s*(?:ew\\.)?paramNameValuePairs\\.([A-Za-z0-9_]+)\\s*\\}");
        Matcher m = p.matcher(sql);
        StringBuffer sb = new StringBuffer();
        while (m.find()) {
            String key = m.group(1);
            Object v = params.get(key);
            String replacement = toSqlLiteral(v);
            // 转义，避免 appendReplacement 解释反斜杠
            replacement = Matcher.quoteReplacement(replacement);
            m.appendReplacement(sb, replacement);
        }
        m.appendTail(sb);
        return sb.toString();
    }

    private static String toSqlLiteral(Object v) {
        if (v == null) return "NULL";
        if (v instanceof Number) return String.valueOf(v);
        if (v instanceof Boolean) return ((Boolean) v) ? "1" : "0";
        if (v instanceof java.time.temporal.TemporalAccessor) return "'" + v.toString().replace("'", "''") + "'";
        if (v instanceof java.util.Date) return "'" + new java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss").format((java.util.Date) v).replace("'", "''") + "'";
        // 默认按字符串处理
        return "'" + String.valueOf(v).replace("'", "''") + "'";
    }

}



