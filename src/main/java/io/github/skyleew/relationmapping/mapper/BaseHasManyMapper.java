package io.github.skyleew.relationmapping.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.baomidou.mybatisplus.core.toolkit.reflect.GenericTypeUtils;
import com.baomidou.mybatisplus.core.toolkit.support.SFunction;
import io.github.skyleew.relationmapping.support.SpringContextHolder;
import io.github.skyleew.relationmapping.service.ExecuteWithSelectService;

public interface BaseHasManyMapper<T,V>  extends BaseMapper<T> {

    default ExecuteWithSelectService<T,V> with() {
        return  with(1); // 默认深度为1

    }

    default ExecuteWithSelectService<T,V> with(int deep) {
          return exeute().setDeep(deep);
   }

//    default ExecuteWithSelectService<T,V> with(HashMap<String, QueryWrapper> relaiton) {
//        return  exeute().setHasRelationMap(relaiton);
//    }
//

    default ExecuteWithSelectService<T,V>  exeute(){
        Class<?> entityClass = GenericTypeUtils.resolveTypeArguments(this.getClass(), BaseHasManyMapper.class)[0];
        Class<?> voclass = GenericTypeUtils.resolveTypeArguments(this.getClass(), BaseHasManyMapper.class)[1];

        ExecuteWithSelectService<T,V> executeWithSelectService = SpringContextHolder.getBean(ExecuteWithSelectService.class);
        return executeWithSelectService.
            load(this,entityClass,voclass);

    }

    /**
     * with 的便捷重载：支持按规则排除某些关联字段的预加载
     * 规则示例：
     * - "posts"                   // 根层字段，等价于 User::posts（Owner 可省略）
     * - "User::posts"            // 指定 Owner 的根层字段
     * - "User::posts.comments"   // 递归路径，排除更深一级
     */
    default ExecuteWithSelectService<T,V> withExcept(String... rules) {
        return with().exclude(rules);
    }

    /**
     * with 的便捷重载：支持通过方法引用排除根层关联字段
     * 示例：withExcept(User::getPosts, User::getProfile)
     */
    // 注意：接口 default 方法不允许使用 final 修饰，这里不加 @SafeVarargs
    // 警告可忽略，或在调用端按需抑制
    default ExecuteWithSelectService<T,V> withExcept(SFunction<T, ?>... props) {
        return with().exclude(props);
    }

    /**
     * with 的便捷重载：仅加载指定关联，其余一律跳过（仅影响 with 预加载）
     * 规则示例：
     * - "posts"                   // 根层字段，等价于 User::posts（Owner 可省略）
     * - "User::posts"            // 指定 Owner 的根层字段
     * - "User::posts.comments"   // 递归路径，仅加载到指定层级
     */
    default ExecuteWithSelectService<T,V> withOnly(String... rules) {
        return with().only(rules);
    }

    /**
     * with 的便捷重载：通过方法引用仅加载根层关联。
     * 示例：withOnly(User::getPosts, User::getProfile)
     */
    default ExecuteWithSelectService<T,V> withOnly(SFunction<T, ?>... props) {
        return with().only(props);
    }

}

