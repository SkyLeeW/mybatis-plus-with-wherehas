package io.github.skyleew.relationmapping.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.baomidou.mybatisplus.core.toolkit.reflect.GenericTypeUtils;
import com.baomidou.mybatisplus.core.toolkit.support.SFunction;
import io.github.skyleew.relationmapping.support.SpringContextHolder;
import io.github.skyleew.relationmapping.service.ExecuteWithSelectService;

/**
 * 提供关联预加载能力的基础 Mapper 接口。
 *
 * @param <T> 主实体类型，用于承接主表查询结果
 * @param <V> 视图类型，用于承接关联装配后的输出结果
 */
public interface BaseHasManyMapper<T,V>  extends BaseMapper<T> {

    /**
     * 创建默认深度为 1 的关联查询服务。
     *
     * @return 返回已设置默认递归深度的关联查询服务实例
     */
    default ExecuteWithSelectService<T,V> with() {
        return  with(1); // 默认深度为1

    }

    /**
     * 创建指定递归深度的关联查询服务。
     *
     * @param deep 需要向下预加载的关联层级深度
     * @return 返回已设置递归深度的关联查询服务实例
     */
    default ExecuteWithSelectService<T,V> with(int deep) {
          return exeute().setDeep(deep);
   }

//    default ExecuteWithSelectService<T,V> with(HashMap<String, QueryWrapper> relaiton) {
//        return  exeute().setHasRelationMap(relaiton);
//    }
//

    /**
     * 创建并初始化当前 Mapper 对应的关联查询服务。
     *
     * @return 返回已绑定实体类型与视图类型的关联查询服务实例
     */
    default ExecuteWithSelectService<T,V>  exeute(){
        Class<?> entityClass = GenericTypeUtils.resolveTypeArguments(this.getClass(), BaseHasManyMapper.class)[0];
        Class<?> voclass = GenericTypeUtils.resolveTypeArguments(this.getClass(), BaseHasManyMapper.class)[1];

        ExecuteWithSelectService<T,V> executeWithSelectService = SpringContextHolder.getBean(ExecuteWithSelectService.class);
        return executeWithSelectService.
            load(this,entityClass,voclass);

    }

    /**
     * with 的便捷重载：支持按规则排除某些关联字段的预加载。
     *
     * @param rules 需要排除的关联规则，支持根字段或多级关联路径
     * @return 返回已附加排除规则的关联查询服务实例
     */
    default ExecuteWithSelectService<T,V> withExcept(String... rules) {
        return with().exclude(rules);
    }

    /**
     * with 的便捷重载：支持通过方法引用排除根层关联字段。
     *
     * @param props 需要排除的根层关联字段方法引用集合
     * @return 返回已附加排除规则的关联查询服务实例
     */
    // 注意：接口 default 方法不允许使用 final 修饰，这里不加 @SafeVarargs
    // 警告可忽略，或在调用端按需抑制
    default ExecuteWithSelectService<T,V> withExcept(SFunction<T, ?>... props) {
        return with().exclude(props);
    }

    /**
     * with 的便捷重载：仅加载指定关联，其余关联一律跳过。
     *
     * @param rules 允许加载的关联规则，支持根字段或多级关联路径
     * @return 返回已附加白名单规则的关联查询服务实例
     */
    default ExecuteWithSelectService<T,V> withOnly(String... rules) {
        return with().only(rules);
    }

    /**
     * with 的便捷重载：通过方法引用仅加载根层关联。
     *
     * @param props 允许加载的根层关联字段方法引用集合
     * @return 返回已附加白名单规则的关联查询服务实例
     */
    default ExecuteWithSelectService<T,V> withOnly(SFunction<T, ?>... props) {
        return with().only(props);
    }

}

