package io.github.skyleew.relationmapping.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;

import java.util.HashMap;
import java.util.List;

@Mapper
public interface RawSqlMapper extends BaseMapper {

    /**
     * 添加此方法以对应XML中的 "executeRawSQL"
     * @param params SQL参数
     * @return 查询结果
     */
    List<Object> executeRawSQL(HashMap<String, Object> params);

    /**
     * 添加此方法以对应XML中的 "executeBindRawSQL"
     * @param params SQL参数
     * @return 查询结果
     */
    List<Object> executeBindRawSQL(HashMap<String, Object> params);

}

