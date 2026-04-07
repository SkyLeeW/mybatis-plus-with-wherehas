package io.github.skyleew.relationmapping.domain;

import lombok.Data;

import java.lang.ref.PhantomReference;

@Data
public class SubSqlResult {

    // 是否为空结果
    private Boolean isEmptyData =false;

    // 是否为空查询条件,
    private Boolean isBlankWhereColumn =false;

    private String sql;

}

