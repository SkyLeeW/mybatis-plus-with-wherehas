package io.github.skyleew.relationmapping.domain;

import lombok.Data;

import java.util.ArrayList;
import java.util.List;

/**
 * 用于承接关联子查询片段、参数列表与空结果状态。
 */
@Data
public class SubSqlResult {

    /**
     * 标记子查询是否可确定为无结果，以便主查询提前结束。
     */
    private Boolean isEmptyData =false;

    /**
     * 标记是否没有可注入主查询的条件片段。
     */
    private Boolean isBlankWhereColumn =false;

    /**
     * 保存可直接注入主查询的 SQL 片段。
     */
    private String sql;

    /**
     * 保存与 SQL 片段中占位符顺序一致的参数列表。
     */
    private List<Object> parameters = new ArrayList<>();

}

