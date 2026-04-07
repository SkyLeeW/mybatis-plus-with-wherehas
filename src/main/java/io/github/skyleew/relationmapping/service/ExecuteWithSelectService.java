package io.github.skyleew.relationmapping.service;

import com.baomidou.mybatisplus.core.conditions.AbstractWrapper;
import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.core.metadata.TableInfo;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.baomidou.mybatisplus.core.toolkit.support.SFunction;
import lombok.RequiredArgsConstructor;
import org.apache.ibatis.reflection.property.PropertyNamer;
import io.github.skyleew.relationmapping.domain.RelationModelStructure;
import io.github.skyleew.relationmapping.domain.SubSqlResult;
import io.github.skyleew.relationmapping.filter.RelationAllowSelector;
import io.github.skyleew.relationmapping.filter.RelationFilter;
import io.github.skyleew.relationmapping.filter.RelationSelector;
import io.github.skyleew.relationmapping.interfaces.WhereClosure;
import io.github.skyleew.relationmapping.support.BeanConversionUtils;
import io.github.skyleew.relationmapping.utils.RelationUtil;
import com.baomidou.mybatisplus.core.toolkit.LambdaUtils;
import org.springframework.beans.factory.config.ConfigurableBeanFactory;
import org.springframework.context.annotation.Scope;
import org.springframework.stereotype.Service;


import java.io.Serializable;
import java.util.*;

/**
 * 用于封装关联查询过程中的预加载、筛选和结果装配能力。
 *
 * @author skyleewy
 * @param <T> 实体类型参数，用于约束主表查询和关联字段引用
 * @param <C> 结果类型参数，用于标识当前查询流程绑定的视图类型
 */
@Service
@RequiredArgsConstructor
@Scope(ConfigurableBeanFactory.SCOPE_PROTOTYPE)
public class ExecuteWithSelectService<T,C> {
    private Class<?> entityClass;

    private Class<?> voClass;

    private  Map<String, RelationModelStructure> relationModelStructureMap;
    private  BaseMapper<T> baseMapper;
    private int deep = 1; // 默认关联深度为1


    private HashMap<String, Wrapper<?>> relationQuery;

    // withWhere: 仅约束 with 预加载的子表，不筛选父表
    private HashMap<String, Wrapper<?>> withRelationQuery;

    /**
     * 关系过滤器：用于本次查询中按需排除部分关联字段的预加载
     */
    private RelationFilter relationFilter;


    final  HasRelationService hasRelationService;
    public ExecuteWithSelectService<T, C> setDeep(int deep) {
              this.deep = deep;
               return this;
          }
    public ExecuteWithSelectService<T, C> setHasRelationMap(HashMap<String, Wrapper<?>> relationQuery) {
        this.relationQuery= relationQuery;
        return  this;
    }

    public ExecuteWithSelectService<T, C> load(BaseMapper<T> baseMapper, Class<?> entityClass,Class<?> voClass ) {
        relationModelStructureMap =   RelationUtil.checkRelation(entityClass);
        this.voClass = voClass;
        this.baseMapper = baseMapper;
        this.entityClass = entityClass;
        return this;
    }


    public ExecuteWithSelectService<T, C> with() {
        return  this;
    }

    /**
     * 仅约束 with 预加载的子表（不筛选父表）。支持根层字段名或路径（a.b）。
     */
    public ExecuteWithSelectService<T, C> withWhere(String key, WhereClosure whereClosure) {
        if (this.withRelationQuery == null) {
            this.withRelationQuery = new HashMap<>();
        }
        QueryWrapper queryWrapper = new QueryWrapper<>();
        whereClosure.where(queryWrapper);
        this.withRelationQuery.put(key, queryWrapper);
        return this;
    }

    /**
     * 仅约束 with 预加载（Lambda 写法）。
     */
    public ExecuteWithSelectService<T, C> withWhere(SFunction<T, ?> sfunction, WhereClosure whereClosure) {
        String fieldName = PropertyNamer.methodToProperty(LambdaUtils.extract(sfunction).getImplMethodName());
        return this.withWhere(fieldName, whereClosure);
    }

    /**
     * 配置需要排除的关联路径（仅影响 with 预加载，不影响 hasWhere）
     * 规则示例："posts"、"User::posts"、"User::posts.comments"
     */
    public ExecuteWithSelectService<T, C> exclude(String... rules) {
        if (rules != null && rules.length > 0) {
            this.relationFilter = new RelationSelector(Arrays.asList(rules));
        }
        return this;
    }

    /**
     * 配置需要排除的关联路径（根层，基于实体 T 的方法引用）。
     * 示例：exclude(User::getPosts, User::getProfile)
     */
    @SafeVarargs
    public final ExecuteWithSelectService<T, C> exclude(SFunction<T, ?>... props) {
        if (props == null || props.length == 0) return this;
        List<String> names = new ArrayList<>();
        for (SFunction<T, ?> fn : props) {
            if (fn == null) continue;
            String fieldName = PropertyNamer.methodToProperty(LambdaUtils.extract(fn).getImplMethodName());
            names.add(fieldName);
        }
        if (!names.isEmpty()) {
            this.relationFilter = new RelationSelector(names);
        }
        return this;
    }

    /**
     * 配置仅允许加载的关联路径（其余全部跳过）。仅影响 with 预加载。
     * 规则示例："posts"、"User::posts"、"User::posts.comments"
     */
    public ExecuteWithSelectService<T, C> only(String... rules) {
        if (rules != null && rules.length > 0) {
            this.relationFilter = new RelationAllowSelector(Arrays.asList(rules));
        }
        return this;
    }

    /**
     * 配置仅允许加载的关联路径（根层，基于实体 T 的方法引用）。
     * 示例：only(User::getPosts, User::getProfile)
     */
    @SafeVarargs
    public final ExecuteWithSelectService<T, C> only(SFunction<T, ?>... props) {
        if (props == null || props.length == 0) return this;
        List<String> names = new ArrayList<>();
        for (SFunction<T, ?> fn : props) {
            if (fn == null) continue;
            String fieldName = PropertyNamer.methodToProperty(LambdaUtils.extract(fn).getImplMethodName());
            names.add(fieldName);
        }
        if (!names.isEmpty()) {
            this.relationFilter = new RelationAllowSelector(names);
        }
        return this;
    }
    /**
     * 新增的重载方法，支持使用 Lambda 方法引用来指定关联字段
     * @param sfunction  一个指向实体类中关联属性的 get 方法的 Lambda (例如: User::getPosts)
     * @param whereClosure  用于构建查询条件的闭包
     * @return ExecuteWithSelectService 实例
     */
    public ExecuteWithSelectService<T, C> hasWhere(SFunction<T, ?> sfunction, WhereClosure whereClosure) {
        // 1. 使用 MyBatis-Plus 工具类将方法引用 (如: User::getPosts) 解析为属性名 (如: "posts")

        String fieldName = PropertyNamer.methodToProperty(LambdaUtils.extract(sfunction).getImplMethodName());

        // 2. 调用原始的 hasWhere 方法，传入解析出的属性名
        return this.hasWhere(fieldName, whereClosure);
    }
    public  ExecuteWithSelectService<T, C> hasWhere(String key, WhereClosure whereClosure) {
        if (this.relationQuery == null) {
            // 用 LinkedHashMap 保序（按调用顺序生成 AND EXISTS），同时仍兼容原 HashMap 字段类型
            this.relationQuery = new LinkedHashMap<>();
        }
        Wrapper<?> existing = this.relationQuery.get(key);
        QueryWrapper queryWrapper;
        if (existing instanceof QueryWrapper) {
            // 允许同一个 key 多次调用 hasWhere 时叠加条件（AND / OR 由调用方在闭包中决定）
            queryWrapper = (QueryWrapper) existing;
        } else {
            queryWrapper = new QueryWrapper<>();
        }
        whereClosure.where(queryWrapper);
        this.relationQuery.put(key, queryWrapper);
        return this;
    }


    /**
     * 查询列表
     * @param queryWrapper
     * @return
     */
    public List<T> selectList(Wrapper<T> queryWrapper) {
        return  this.sqlSelectList(queryWrapper);
    }



    /**
     * 查询sql数据
     * @param queryWrapper
     * @return
     */
    private   List<T> sqlSelectList(Wrapper<T> queryWrapper) {
        if (queryWrapper == null) {
            queryWrapper = new QueryWrapper();
        }
        //如果没有where子句，返回空果
        if(hasWhereSql(queryWrapper).getIsEmptyData()){
            return new ArrayList<>();
        }

        List<T> t = baseMapper.selectList(queryWrapper);
        if (this.relationModelStructureMap.isEmpty()) {
            return  t;
        }

       // 根据是否配置过滤器，选择带过滤器的重载或原有方法（保持兼容）
       if (this.relationFilter != null) {
           hasRelationService.relationModel(this.entityClass, this.relationModelStructureMap, t, this.deep, this.relationFilter, "", this.withRelationQuery);
       } else {
           hasRelationService.relationModel(this.entityClass, this.relationModelStructureMap, t, this.deep, null, "", this.withRelationQuery);
       }

        return t;
    }

    /**
     * 查询列表
     * @param page
     * @param queryWrapper
     * @return
     */
    private   List<T> sqlSelectList(IPage page, Wrapper<T> queryWrapper) {

        if (queryWrapper == null) {
            queryWrapper = new QueryWrapper();
        }
        //如果没有where子句，返回空果
        if(hasWhereSql(queryWrapper).getIsEmptyData()){
            return new ArrayList<>();
        }
        List<T> t = baseMapper.selectList(page,queryWrapper);
        if (this.relationModelStructureMap.isEmpty()) {
            return  t;
        }
        if (this.relationFilter != null) {
            hasRelationService.relationModel(this.entityClass, this.relationModelStructureMap, t, this.deep, this.relationFilter, "", this.withRelationQuery);
        } else {
            hasRelationService.relationModel(this.entityClass, this.relationModelStructureMap, t, this.deep, null, "", this.withRelationQuery);
        }
        return t;
    }

    /**
     * sql 查询 单条
     * @param queryWrapper
     * @return
     */
    private   T sqlSelectOne(Wrapper<T> queryWrapper) {
        if (queryWrapper == null) {
            queryWrapper = new QueryWrapper<>();
        }
        //如果没有where子句，返回空果
        if(hasWhereSql(queryWrapper).getIsEmptyData()){
            return  null;
        }
        ((AbstractWrapper<?, ?, ?>) queryWrapper).last("limit 1");
        Object t = baseMapper.selectOne(queryWrapper);
        if (this.relationModelStructureMap.isEmpty()) {
            return (T) t;
        }
        if (this.relationFilter != null) {
            hasRelationService.relationModel(this.entityClass, this.relationModelStructureMap, t, this.deep, this.relationFilter, "", this.withRelationQuery);
        } else {
            hasRelationService.relationModel(this.entityClass, this.relationModelStructureMap, t, this.deep, null, "", this.withRelationQuery);
        }
        return (T) t;
    }

    public  <P extends IPage<T>> P selectPage(P page,Wrapper<T> queryWrapper) {
        List<T> t = this.sqlSelectList(page,queryWrapper);
        page.setRecords(t);
        return page;
    }

    public   T selectOne(Wrapper<T> queryWrapper) {
        return  this.sqlSelectOne(queryWrapper);
    }

    /**
     * 根据主键查询单个实体，并自动按当前 with/hasWhere 配置加载关联数据。
     * 优先使用主键列构造 Wrapper，走统一的 sqlSelectOne 流程；
     * 若无法解析主键列，则退回 baseMapper.selectById 再做关联填充。
     */
    public T selectById(Serializable id) {
        if (id == null) {
            return null;
        }
        TableInfo tableInfo = TableInfoHelper.getTableInfo(this.entityClass);
        if (tableInfo == null || tableInfo.getKeyColumn() == null) {
            // fallback：无法解析主键列时，直接用 BaseMapper，再手动做关联预加载
            T entity = baseMapper.selectById(id);
            if (entity == null || this.relationModelStructureMap.isEmpty()) {
                return entity;
            }
            if (this.relationFilter != null) {
                hasRelationService.relationModel(this.entityClass, this.relationModelStructureMap, entity, this.deep, this.relationFilter, "", this.withRelationQuery);
            } else {
                hasRelationService.relationModel(this.entityClass, this.relationModelStructureMap, entity, this.deep, null, "", this.withRelationQuery);
            }
            return entity;
        }
        QueryWrapper<T> qw = new QueryWrapper<>();
        qw.eq(tableInfo.getKeyColumn(), id);
        return this.selectOne(qw);
    }

    /**
     * 根据主键集合查询实体列表，并按当前 with/hasWhere 配置加载关联数据。
     */
    public List<T> selectByIds(Collection<? extends Serializable> ids) {
        if (ids == null || ids.isEmpty()) {
            return Collections.emptyList();
        }
        TableInfo tableInfo = TableInfoHelper.getTableInfo(this.entityClass);
        if (tableInfo == null || tableInfo.getKeyColumn() == null) {
            // fallback：无法解析主键列时，直接用 BaseMapper，再手动做关联预加载
            List<T> list = baseMapper.selectBatchIds(ids);
            if (list == null || list.isEmpty() || this.relationModelStructureMap.isEmpty()) {
                return list == null ? Collections.emptyList() : list;
            }
            if (this.relationFilter != null) {
                hasRelationService.relationModel(this.entityClass, this.relationModelStructureMap, list, this.deep, this.relationFilter, "", this.withRelationQuery);
            } else {
                hasRelationService.relationModel(this.entityClass, this.relationModelStructureMap, list, this.deep, null, "", this.withRelationQuery);
            }
            return list;
        }
        QueryWrapper<T> qw = new QueryWrapper<>();
        qw.in(tableInfo.getKeyColumn(), ids);
        return this.selectList(qw);
    }

    public C selectVoOne(Wrapper<T> queryWrapper) {
        T t = this.sqlSelectOne(queryWrapper);
        return (C) BeanConversionUtils.convert(t, voClass);
    }

    /**
     * 根据主键查询单个 VO（使用泛型 C）。
     */
    public C selectVoById(Serializable id) {
        T entity = this.selectById(id);
        return (C) BeanConversionUtils.convert(entity, voClass);
    }

    /**
     * 根据主键集合查询 VO 列表（使用泛型 C）。
     */
    public List<C> selectVoByIds(Collection<? extends Serializable> ids) {
        List<T> list = this.selectByIds(ids);
        return (List<C>) BeanConversionUtils.convertToList(list, voClass);
    }

    public <P extends IPage<C>> P selectVoPage(P page, Wrapper<T> queryWrapper) {
        List<T> t = this.sqlSelectList(page, queryWrapper);
        page.setRecords((List<C>) BeanConversionUtils.convertToList(t, voClass));
        return (P) page;
    }


    public List<C> selectVoList(Wrapper<T> queryWrapper) {
        List<T> l = this.sqlSelectList(queryWrapper);
        return (List<C>) BeanConversionUtils.convertToList(l, voClass);
    }

    /**
     * 动态 VO：不依赖泛型 C，调用时传入目标 VO Class。
     * 适用于一个实体需要转换为多个不同 VO 的场景。
     */
    public <V> V selectVoOne(Wrapper<T> queryWrapper, Class<V> voClassDynamic) {
        T t = this.sqlSelectOne(queryWrapper);
        return BeanConversionUtils.convert(t, voClassDynamic);
    }

    public <V, P extends IPage<V>> P selectVoPage(P page, Wrapper<T> queryWrapper, Class<V> voClassDynamic) {
        List<T> t = this.sqlSelectList(page, queryWrapper);
        // BeanConversionUtils.convert 会对空集合返回空 List
        page.setRecords(BeanConversionUtils.convertToList(t, voClassDynamic));
        return page;
    }

    public <V> List<V> selectVoList(Wrapper<T> queryWrapper, Class<V> voClassDynamic) {
        List<T> l = this.sqlSelectList(queryWrapper);
        return BeanConversionUtils.convertToList(l, voClassDynamic);
    }

    /**
     * 根据主键查询单个 VO（动态 VO 版本）。
     */
    public <V> V selectVoById(Serializable id, Class<V> voClassDynamic) {
        T entity = this.selectById(id);
        return BeanConversionUtils.convert(entity, voClassDynamic);
    }

    /**
     * 根据主键集合查询 VO 列表（动态 VO 版本）。
     */
    public <V> List<V> selectVoByIds(Collection<? extends Serializable> ids, Class<V> voClassDynamic) {
        List<T> list = this.selectByIds(ids);
        return BeanConversionUtils.convertToList(list, voClassDynamic);
    }


    /*
     *  hasWhere 得到是否需要注入的sql子语句
     */
    private SubSqlResult hasWhereSql(Wrapper<?> queryWrapper){
        SubSqlResult subWhere = new SubSqlResult();
        subWhere.setIsBlankWhereColumn(true);
        if (this.relationQuery != null && !this.relationModelStructureMap.isEmpty()){
            // 调用关联子查询构造逻辑并把条件参数一并回传给主查询。
            subWhere = hasRelationService.relationModelQuery(this.entityClass, this.relationModelStructureMap, this.relationQuery);
            if(subWhere.getIsBlankWhereColumn() || subWhere.getIsEmptyData()){
                return  subWhere;
            } else {
                List<Object> parameters = subWhere.getParameters();
                ((AbstractWrapper<?, ?, ?>) queryWrapper).apply(
                    subWhere.getSql(),
                    parameters == null ? new Object[0] : parameters.toArray()
                );
            }
            return subWhere;
        }
        return subWhere;
    }



}







