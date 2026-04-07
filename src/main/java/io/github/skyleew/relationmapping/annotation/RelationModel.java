package io.github.skyleew.relationmapping.annotation;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

@Retention(RetentionPolicy.RUNTIME)
@Target({ElementType.FIELD})
public @interface RelationModel {
    String field() default "id";
    String targetField() default "";
    boolean count() default false;

    /**
     * 新增属性：用于显式指定关联的目标实体类。
     * 特别是在进行 count 统计时，此项为必需。
     * @return 目标实体类的 Class 对象
     */
    Class<?> table() default void.class; // 使用 void.class 作为未设置的默认值
}

