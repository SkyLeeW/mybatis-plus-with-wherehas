package io.github.skyleew.relationmapping.filter;

/**
 * 关系过滤器：用于在 with 预加载时按路径跳过指定关联。
 *
 * - ownerClass: 当前遍历到的“所属实体类”（递归时会变）
 * - fieldPath: 以点号描述的路径（根层为当前字段名，递归时为 parent.child 形式）
 * - isRootLevel: 是否根层（根实体的第一层字段）
 */
public interface RelationFilter {
    /**
     * 返回 true 表示跳过该路径的关联加载
     */
    boolean skip(Class<?> ownerClass, String fieldPath, boolean isRootLevel);
}


