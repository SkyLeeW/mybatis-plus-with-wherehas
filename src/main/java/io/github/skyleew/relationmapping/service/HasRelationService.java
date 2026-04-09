package io.github.skyleew.relationmapping.service;

import cn.hutool.core.util.StrUtil;
import com.baomidou.mybatisplus.core.conditions.Wrapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import io.github.skyleew.relationmapping.domain.RelationModelStructure;
import io.github.skyleew.relationmapping.domain.SubSqlResult;
import io.github.skyleew.relationmapping.domain.TableStructure;
import io.github.skyleew.relationmapping.support.BeanConversionUtils;
import io.github.skyleew.relationmapping.filter.RelationFilter;
import io.github.skyleew.relationmapping.utils.RelationUtil;
import io.github.skyleew.relationmapping.utils.ReflectionFieldUtils;
import org.springframework.stereotype.Service;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Service
@RequiredArgsConstructor
@Slf4j
public class HasRelationService {

    /**
     * 匹配 apply 使用的顺序占位符，便于多段 EXISTS 合并时统一重排索引。
     */
    private static final Pattern APPLY_PARAMETER_PATTERN = Pattern.compile("\\{(\\d+)\\}");

    final RelationSqlService relationSqlService;

    /**
     * 关联查询
     * @param selfModelEntityClass
     * @param tableStructureMap
     * @param result
     */
    public void relationModel(Class<?> selfModelEntityClass , Map<String, RelationModelStructure> tableStructureMap , Object result, int deep) {
        if (selfModelEntityClass == null || result == null|| deep <= 0) {
            return;
        }
        try {
            for (String key : tableStructureMap.keySet()) {
                RelationModelStructure table =  tableStructureMap.get(key);
                getResultRelationIds(result,table);
                if (table.getSelfTableStructure().getRelationValues() == null || table.getSelfTableStructure().getRelationValues().isEmpty()) {
                    continue;
                }
                // 根据 isCount 标志决定执行哪种查询
                if (table.isCount()) {
                    // 执行 Count 查询
                    Map<String, Long> countResult = relationSqlService.getCountResult(table);
                    if (countResult != null && !countResult.isEmpty()) {
                        resultCountMerge(result, countResult, table);
                    }
                } else {
                    // 执行原有的关联查询
                    List<Object> relationResult = relationSqlService.getResult(table);
                    if (relationResult != null && !relationResult.isEmpty()) {
                        List<Object> castedRelationResult =   resultRelationMerge(result, relationResult, table);
                        // --- 递归调用以实现深度查询 ---
                        if (!castedRelationResult.isEmpty()) { // 判断转换后的列表
                            Class<?> nextLevelClass = table.getRelationTableStructure().getReflectClass();
                            Map<String, RelationModelStructure> nextLevelRelations = RelationUtil.checkRelation(nextLevelClass);

                            if (!nextLevelRelations.isEmpty()) {
                                // 使用类型正确的 castedRelationResult 进行递归
                                relationModel(nextLevelClass, nextLevelRelations, castedRelationResult, deep - 1);
                            }
                        }
                    }
                }
            }
        }catch (Exception e){
            log.error("关联查询错误", e);
        }

    }

    /**
     * 关联查询（带过滤器版本）：可按规则跳过某些字段/路径的预加载
     *
     * @param selfModelEntityClass 当前所属实体类（根层为主实体，递归时为子实体）
     * @param tableStructureMap    当前层的关联元数据集合（字段名 -> 结构）
     * @param result               已查询到的主表结果（列表或单对象）
     * @param deep                 递归深度控制
     * @param relationFilter       关系过滤器（为空则等价于不过滤）
     * @param parentPath           父路径（根层为空字符串）
     */
    public void relationModel(Class<?> selfModelEntityClass,
                              Map<String, RelationModelStructure> tableStructureMap,
                              Object result,
                              int deep,
                              RelationFilter relationFilter,
                              String parentPath) {
        if (selfModelEntityClass == null || result == null || deep <= 0) {
            return;
        }
        try {
            for (String key : tableStructureMap.keySet()) {
                // 计算当前路径（根层为字段名，递归层为 parent.child）
                String currentPath = StrUtil.isBlank(parentPath) ? key : parentPath + "." + key;
                boolean rootLevel = StrUtil.isBlank(parentPath);

                // 如果命中过滤规则，则跳过本字段的关联加载（含 count）
                if (relationFilter != null && relationFilter.skip(selfModelEntityClass, currentPath, rootLevel)) {
                    continue;
                }

                RelationModelStructure table = tableStructureMap.get(key);
                getResultRelationIds(result, table);
                if (table.getSelfTableStructure().getRelationValues() == null || table.getSelfTableStructure().getRelationValues().isEmpty()) {
                    continue;
                }

                if (table.isCount()) {
                    Map<String, Long> countResult = relationSqlService.getCountResult(table);
                    if (countResult != null && !countResult.isEmpty()) {
                        resultCountMerge(result, countResult, table);
                    }
                } else {
                    List<Object> relationResult = relationSqlService.getResult(table);
                    if (relationResult != null && !relationResult.isEmpty()) {
                        List<Object> castedRelationResult = resultRelationMerge(result, relationResult, table);
                        if (!castedRelationResult.isEmpty()) {
                            Class<?> nextLevelClass = table.getRelationTableStructure().getReflectClass();
                            Map<String, RelationModelStructure> nextLevelRelations = RelationUtil.checkRelation(nextLevelClass);
                            if (!nextLevelRelations.isEmpty()) {
                                relationModel(nextLevelClass, nextLevelRelations, castedRelationResult, deep - 1, relationFilter, currentPath);
                            }
                        }
                    }
                }
            }
        } catch (Exception e) {
            log.error("关联查询错误", e);
        }
    }

    /**
     * 关联查询（带过滤器 + withWhere 条件）：按需约束预加载的子表，不筛选父表。
     * 支持用路径（root.child）或根层字段名匹配 withWhere 条件。
     */
    public void relationModel(Class<?> selfModelEntityClass,
                              Map<String, RelationModelStructure> tableStructureMap,
                              Object result,
                              int deep,
                              RelationFilter relationFilter,
                              String parentPath,
                              HashMap<String, Wrapper<?>> withQueryMap) {
        if (selfModelEntityClass == null || result == null || deep <= 0) {
            return;
        }
        try {
            for (String key : tableStructureMap.keySet()) {
                String currentPath = StrUtil.isBlank(parentPath) ? key : parentPath + "." + key;
                boolean rootLevel = StrUtil.isBlank(parentPath);

                if (relationFilter != null && relationFilter.skip(selfModelEntityClass, currentPath, rootLevel)) {
                    continue;
                }

                RelationModelStructure table = tableStructureMap.get(key);
                getResultRelationIds(result, table);
                if (table.getSelfTableStructure().getRelationValues() == null || table.getSelfTableStructure().getRelationValues().isEmpty()) {
                    continue;
                }

                if (table.isCount()) {
                    Map<String, Long> countResult = relationSqlService.getCountResult(table);
                    if (countResult != null && !countResult.isEmpty()) {
                        resultCountMerge(result, countResult, table);
                    }
                } else {
                    // 匹配 withWhere 条件：优先路径，其次根字段名
                    Wrapper<?> w = null;
                    if (withQueryMap != null) {
                        w = withQueryMap.get(currentPath);
                        if (w == null && rootLevel) {
                            w = withQueryMap.get(key);
                        }
                    }
                    List<Object> relationResult = (w == null)
                        ? relationSqlService.getResult(table)
                        : relationSqlService.getResultWithWhere(table, w);

                    if (relationResult != null && !relationResult.isEmpty()) {
                        List<Object> castedRelationResult = resultRelationMerge(result, relationResult, table);
                        if (!castedRelationResult.isEmpty()) {
                            Class<?> nextLevelClass = table.getRelationTableStructure().getReflectClass();
                            Map<String, RelationModelStructure> nextLevelRelations = RelationUtil.checkRelation(nextLevelClass);
                            if (!nextLevelRelations.isEmpty()) {
                                relationModel(nextLevelClass, nextLevelRelations, castedRelationResult, deep - 1, relationFilter, currentPath, withQueryMap);
                            }
                        }
                    }
                }
            }
        } catch (Exception e) {
            log.error("关联查询错误", e);
        }
    }


    /**
     * 关联子查询的where(先查子查询,再查主查询)
     * @param selfModelEntityClass
     * @param tableStructureMap
     * @param relationQuery (1. 修改这里的参数类型)
     */
    public SubSqlResult relationModelQuery(Class<?> selfModelEntityClass, Map<String, RelationModelStructure> tableStructureMap, HashMap<String, Wrapper<?>> relationQuery) {
        //用于异常返回
        SubSqlResult subSqlResult = new SubSqlResult();
        subSqlResult.setIsBlankWhereColumn(true);

        if (selfModelEntityClass == null) {
            return subSqlResult;
        }
        try {
            if (relationQuery == null || relationQuery.isEmpty() || tableStructureMap == null || tableStructureMap.isEmpty()) {
                return subSqlResult;
            }

            // 支持配置多个 hasWhere：多个 EXISTS 以 AND 组合
            List<String> existsSqlList = new ArrayList<>();
            for (Map.Entry<String, Wrapper<?>> entry : relationQuery.entrySet()) {
                String key = entry.getKey();
                Wrapper<?> queryWrapper = entry.getValue();
                if (queryWrapper == null) continue;

                RelationModelStructure table = tableStructureMap.get(key);
                if (table == null) {
                    // key 不存在于关系元数据中时跳过（避免影响其它 hasWhere）
                    continue;
                }

                SubSqlResult one = relationSqlService.getSubSelectResult(table, queryWrapper);
                if (Boolean.TRUE.equals(one.getIsEmptyData())) {
                    // AND 语义：任一子条件预判为空，则整体为空
                    subSqlResult.setIsEmptyData(true);
                    subSqlResult.setIsBlankWhereColumn(false);
                    return subSqlResult;
                }
                if (Boolean.TRUE.equals(one.getIsBlankWhereColumn()) || StrUtil.isBlank(one.getSql())) {
                    // 空条件不参与拼装（也不阻断其它 hasWhere）
                    continue;
                }
                int parameterOffset = subSqlResult.getParameters().size();
                String reindexedSql = reindexApplyPlaceholders(one.getSql(), parameterOffset);
                existsSqlList.add("(" + reindexedSql + ")");
                subSqlResult.getParameters().addAll(one.getParameters());
            }

            if (!existsSqlList.isEmpty()) {
                subSqlResult.setIsBlankWhereColumn(false);
                subSqlResult.setSql(String.join(" AND ", existsSqlList));
            }
        } catch (Exception e) {
            log.error("关联查询错误", e);
        }
        return subSqlResult;
    }

    /**
     * 多个子查询片段合并到一次 apply 调用时，需要把每段局部占位符改成全局顺序索引。
     *
     * @param sql 原始 SQL 片段
     * @param parameterOffset 当前片段参数在总参数列表中的起始偏移量
     * @return 重排后的 SQL 片段
     */
    private String reindexApplyPlaceholders(String sql, int parameterOffset) {
        if (StrUtil.isBlank(sql) || parameterOffset == 0) {
            return sql;
        }
        Matcher matcher = APPLY_PARAMETER_PATTERN.matcher(sql);
        StringBuffer stringBuffer = new StringBuffer();
        while (matcher.find()) {
            int currentIndex = Integer.parseInt(matcher.group(1));
            int targetIndex = currentIndex + parameterOffset;
            matcher.appendReplacement(stringBuffer, Matcher.quoteReplacement("{" + targetIndex + "}"));
        }
        matcher.appendTail(stringBuffer);
        return stringBuffer.toString();
    }


    /**
     * 结果合并
     * @param result
     * @param relationResult
     * @param modelStructure
     * @throws NoSuchFieldException
     * @throws IllegalAccessException
     */
    private  List<Object>  resultRelationMerge(Object result,List<Object> relationResult,RelationModelStructure modelStructure) throws NoSuchFieldException, IllegalAccessException {

        if (relationResult == null || relationResult.isEmpty()) { // 增加判空
            return Collections.emptyList();
        }

//         //判断是否是一对一,还是一对多
        TableStructure selfTableStructure = modelStructure.getSelfTableStructure();
        TableStructure relationTableStructure = modelStructure.getRelationTableStructure();


        // 用于收集转换后的真实实体对象
        List<Object> castedObjects = new ArrayList<>();
        //得到目标的用于关联的关联字段,进行分组
        relationTableStructure.getRelationField().setAccessible(true);
        Map<String, List<Object>> groupedByDynamicField = new HashMap<>();
        for (Object o : relationResult) {
            Object tobj = convertRelationRecord(o, relationTableStructure);
            castedObjects.add(tobj);

            String groupKey = StrUtil.toString(relationTableStructure.getRelationField().get(tobj));
            //分组,不存在则初始化数组
            groupedByDynamicField.computeIfAbsent(groupKey, k -> new ArrayList<>()).add(tobj);

            //
            if (result instanceof List<?>) {
                for (Object item : (List<?>) result) {
                    putRelation(item,selfTableStructure,groupedByDynamicField);
                }
            }else{
                putRelation(result,selfTableStructure,groupedByDynamicField);
            }

        }

        return castedObjects;

    }

    /**
     * 将关联查询结果统一转换为目标实体，已是目标类型时直接复用原对象。
     *
     * @param source 原始关联结果
     * @param relationTableStructure 关联表结构
     * @return 目标实体对象
     */
    @SuppressWarnings("unchecked")
    private Object convertRelationRecord(Object source, TableStructure relationTableStructure) {
        Class<Object> relationClass = (Class<Object>) relationTableStructure.getReflectClass();
        if (relationClass.isInstance(source)) {
            return relationClass.cast(source);
        }
        return BeanConversionUtils.convert(source, relationClass);
    }



    /**
     *
     *  将关联数据放入目标对象
     * @param result
     * @param selfTableStructure
     * @param groupedByDynamicField
     * @throws NoSuchFieldException
     * @throws IllegalAccessException
     */
    private void  putRelation(Object result, TableStructure selfTableStructure, Map<String, List<Object>> groupedByDynamicField) throws NoSuchFieldException, IllegalAccessException {
        Class<?> tableClass =  result.getClass();
        Field s = resolveField(tableClass, selfTableStructure.getRelationField(), selfTableStructure.getRelationField().getName());
        Field putFiled = resolveField(tableClass, selfTableStructure.getAnnotationField(), selfTableStructure.getAnnotationField().getName());

        String unionKey = StrUtil.toString(s.get(result));
        List<Object> groupData =  groupedByDynamicField.get(unionKey);

        if(groupData !=null){

            if (Collection.class.isAssignableFrom(putFiled.getType())) {
                putFiled.set(result, buildCollectionValue(putFiled.getType(), groupData));
            } else {
                putFiled.set(result, groupData.get(0));
            }

        }
    }




    /**
     *  得到关联的id 得到一个用于做关联查询的 id (1,2,3,4)这种
     */
    private void getResultRelationIds(Object result , RelationModelStructure table) throws NoSuchFieldException, IllegalAccessException {

        List<Object> ids = new ArrayList<>();
        TableStructure selfTableStructure = table.getSelfTableStructure();
        selfTableStructure.setRelationValues(new ArrayList<>());
        selfTableStructure.setIds("");
        //判断是否是数组
        if (result instanceof List<?>) {
            for (Object o : (List<?>) result) {
                Object idValue = null;
                // --- 新增：判断对象是否为 Map ---
                if (o instanceof Map) {
                    // 如果是 Map，使用 relationFieldKey (通常是数据库列名) 作为 key 来获取值
                    idValue = ((Map<?, ?>) o).get(selfTableStructure.getRelationFieldKey());
                } else {
                    // 如果是实体对象，优先复用元数据中的字段对象并支持继承树字段。
                    idValue = readFieldValue(o, selfTableStructure.getRelationField(), selfTableStructure.getRelationField().getName());
                }

                if (idValue != null) {
                    ids.add(idValue);
                }
            }
        } else {
            Object idValue = readFieldValue(result, selfTableStructure.getRelationField(), selfTableStructure.getRelationField().getName());
            if (idValue != null) {
                ids.add(idValue);
            }
        }
        if (ids.isEmpty()) {
            return;
        }
        // 去重，保留顺序，避免生成 IN (1,1,1,1) 这种 SQL
        LinkedHashSet<Object> distinctIds = new LinkedHashSet<>(ids);
        selfTableStructure.setRelationValues(new ArrayList<>(distinctIds));
        selfTableStructure.setIds(
            distinctIds.stream().map(String::valueOf).reduce((left, right) -> left + "," + right).orElse("")
        );
    }

    /**
     * 新增方法：将统计结果合并到主模型对象中
     */
    private void resultCountMerge(Object result, Map<String, Long> countMap, RelationModelStructure modelStructure) throws NoSuchFieldException, IllegalAccessException {
        if (result instanceof List<?>) {
            for (Object item : (List<?>) result) {
                putCount(item, countMap, modelStructure);
            }
        } else {
            putCount(result, countMap, modelStructure);
        }
    }
    /**
     * 新增方法：为单个对象设置统计数量
     */
    private void putCount(Object item, Map<String, Long> countMap, RelationModelStructure modelStructure) throws NoSuchFieldException, IllegalAccessException {
        TableStructure selfTable = modelStructure.getSelfTableStructure();
        Class<?> itemClass = item.getClass();

        // 获取主模型中用于关联的字段 (例如 User 实体中的 id 字段)
        Field keyField = resolveField(itemClass, selfTable.getRelationField(), selfTable.getRelationField().getName());
        String key = StrUtil.toString(keyField.get(item));

        // 获取主模型中用于接收统计数量的字段 (例如 User 实体中的 postsCount 字段)
        Field targetField = resolveField(itemClass, selfTable.getAnnotationField(), selfTable.getAnnotationField().getName());

        // 从 countMap 中查找对应的数量，如果找不到默认为 0
        Long count = countMap.getOrDefault(key, 0L);

        // 根据目标字段的类型（Integer 或 Long）进行设置
        if (targetField.getType() == Integer.class || targetField.getType() == int.class) {
            targetField.set(item, count.intValue());
        } else {
            targetField.set(item, count);
        }
    }

    /**
     * 统一读取对象字段值，优先使用缓存的 Field，并兼容字段定义在父类的场景。
     *
     * @param target 目标对象
     * @param preferredField 优先使用的字段元数据
     * @param fieldName 字段名
     * @return 字段值
     * @throws IllegalAccessException 字段不可访问时抛出异常
     * @throws NoSuchFieldException 字段不存在时抛出异常
     */
    private Object readFieldValue(Object target, Field preferredField, String fieldName) throws IllegalAccessException, NoSuchFieldException {
        Field field = resolveField(target.getClass(), preferredField, fieldName);
        return field.get(target);
    }

    /**
     * 统一解析字段对象，支持直接复用已有元数据，也支持沿继承树回查缺失字段。
     *
     * @param targetClass 目标运行时类型
     * @param preferredField 优先字段
     * @param fieldName 字段名
     * @return 可直接访问的字段
     * @throws NoSuchFieldException 字段不存在时抛出异常
     */
    private Field resolveField(Class<?> targetClass, Field preferredField, String fieldName) throws NoSuchFieldException {
        Field field = preferredField;
        if (field == null || !field.getDeclaringClass().isAssignableFrom(targetClass)) {
            field = ReflectionFieldUtils.findField(targetClass, fieldName);
        }
        if (field == null) {
            throw new NoSuchFieldException(fieldName);
        }
        field.setAccessible(true);
        return field;
    }

    /**
     * 按目标字段的集合语义构造回填对象，保持列表、有序集合和具体集合实现的行为一致。
     *
     * @param targetType 目标字段类型
     * @param sourceData 关联结果列表
     * @return 适配后的集合对象
     */
    private Collection<Object> buildCollectionValue(Class<?> targetType, List<Object> sourceData) {
        Collection<Object> collection = instantiateCollection(targetType);
        collection.addAll(sourceData);
        return collection;
    }

    /**
     * 根据字段声明类型实例化集合对象，接口类型会回落到最接近语义的标准集合实现。
     *
     * @param targetType 目标字段类型
     * @return 可写入字段的集合实例
     */
    @SuppressWarnings("unchecked")
    private Collection<Object> instantiateCollection(Class<?> targetType) {
        if (!targetType.isInterface() && !Modifier.isAbstract(targetType.getModifiers())) {
            try {
                Object instance = targetType.getDeclaredConstructor().newInstance();
                if (instance instanceof Collection<?>) {
                    return (Collection<Object>) instance;
                }
            } catch (Exception ignored) {
            }
        }
        if (Set.class.isAssignableFrom(targetType)) {
            return new LinkedHashSet<>();
        }
        if (Queue.class.isAssignableFrom(targetType) || Deque.class.isAssignableFrom(targetType)) {
            return new ArrayDeque<>();
        }
        return new ArrayList<>();
    }





}

