package io.github.skyleew.relationmapping.interfaces;

import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;

@FunctionalInterface
public interface WhereClosure<T, C> {
    QueryWrapper<T> where(QueryWrapper<?> queryWrapper);
}

