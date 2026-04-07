package io.github.skyleew.relationmapping.utils;

import cn.hutool.core.util.StrUtil;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableLogic;
import com.baomidou.mybatisplus.core.metadata.TableFieldInfo;
import com.baomidou.mybatisplus.core.metadata.TableInfo;
import lombok.extern.slf4j.Slf4j;
import io.github.skyleew.relationmapping.support.SpringContextHolder;
import org.springframework.core.env.Environment;

import java.lang.reflect.Field;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.Date;
import java.util.Optional;

/**
 * 基于 MyBatis-Plus 的 @TableLogic 构建“未删除”SQL 条件辅助类。
 *
 * 兼容多种数据格式：
 * - 字符串/字符：如 'Y'/'N'、'0'/'1'，自动加引号
 * - 数值：0/1/2 等，尽量作为数值字面量
 * - 布尔：true/false（在不同数据库尽量保持通用）
 * - 日期/时间：按需使用 IS NULL 代表“未删除”（适配时间戳软删）
 */
@Slf4j
public class LogicDeleteHelper {

    /**
     * 根据实体上的 @TableLogic 与全局配置，构建“未删除”SQL 条件。
     * 若实体无逻辑删除字段则返回 empty。
     */
    public static Optional<String> buildNotDeletedPredicate(Class<?> entityClass, TableInfo tableInfo) {
        if (entityClass == null || tableInfo == null) return Optional.empty();

        // 查找标注了 @TableLogic 的字段
        Field logicField = null;
        for (Field f : entityClass.getDeclaredFields()) {
            if (f.getAnnotation(TableLogic.class) != null) {
                logicField = f;
                break;
            }
        }
        if (logicField == null) return Optional.empty();

        // 使用 TableInfo 获取数据库列名（优先级最高）
        String column = getColumnName(tableInfo, logicField);
        if (StrUtil.isBlank(column)) return Optional.empty();

        // 解析“未删除”值
        TableLogic logicAnno = logicField.getAnnotation(TableLogic.class);
        String notDelRaw = null;
        if (logicAnno != null) {
            // value() 为未删除值，delval() 为已删除值
            if (StrUtil.isNotBlank(logicAnno.value())) {
                notDelRaw = logicAnno.value();
            }
        }
        if (StrUtil.isBlank(notDelRaw)) {
            notDelRaw = readGlobalNotDeleteValue().orElse("0");
        }

        // 当配置为 NULL 或字段为时间类型时，默认使用 IS NULL 代表“未删除”
        if ("NULL".equalsIgnoreCase(notDelRaw) || isDateTimeType(logicField.getType())) {
            return Optional.of(column + " IS NULL");
        }

        Class<?> type = logicField.getType();
        String literal;
        try {
            if (CharSequence.class.isAssignableFrom(type)) {
                literal = quoteSql(notDelRaw);
            } else if (type == Character.class || type == char.class) {
                // 若为字符类型，取配置字符串的首字符，若为空则使用空字符'\0'
                literal = quoteSql(String.valueOf(notDelRaw.length() > 0 ? notDelRaw.charAt(0) : '\0'));
            } else if (Number.class.isAssignableFrom(type)
                || type.isPrimitive() && (type == int.class || type == long.class || type == short.class || type == byte.class || type == double.class || type == float.class)
                || BigInteger.class.isAssignableFrom(type)
                || BigDecimal.class.isAssignableFrom(type)) {
                // 尽量保留为数值字面量，避免数据库类型转换
                if (isNumeric(notDelRaw)) {
                    literal = notDelRaw;
                } else {
                    // 回落到字符串字面量，避免非数值配置导致 SQL 报错
                    literal = quoteSql(notDelRaw);
                }
            } else if (type == Boolean.class || type == boolean.class) {
                // 归一化为布尔字面量，提升跨数据库兼容性
                boolean b = parseBoolean(notDelRaw);
                literal = b ? "TRUE" : "FALSE";
            } else if (isDateTimeType(type)) {
                // 日期/时间字段：为了通用性仍回落为 IS NULL 模式
                return Optional.of(column + " IS NULL");
            } else if (type.isEnum()) {
                literal = quoteSql(notDelRaw);
            } else {
                // 其他未知类型：保守使用字符串字面量
                literal = quoteSql(notDelRaw);
            }
        } catch (Exception e) {
            log.warn("构建逻辑未删除字面量失败 {}.{}: {}", entityClass.getSimpleName(), logicField.getName(), e.getMessage());
            literal = quoteSql(notDelRaw);
        }

        return Optional.of(column + " = " + literal);
    }

    private static boolean isNumeric(String s) {
        if (s == null) return false;
        try {
            new BigDecimal(s);
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    private static boolean parseBoolean(String s) {
        if (s == null) return false;
        String v = s.trim().toLowerCase();
        if ("1".equals(v) || "true".equals(v) || "t".equals(v) || "yes".equals(v) || "y".equals(v)) return true;
        if ("0".equals(v) || "false".equals(v) || "f".equals(v) || "no".equals(v) || "n".equals(v)) return false;
        // Fallback to Java's parser
        return Boolean.parseBoolean(v);
    }

    private static boolean isDateTimeType(Class<?> type) {
        return Date.class.isAssignableFrom(type)
            || java.sql.Date.class.isAssignableFrom(type)
            || java.sql.Timestamp.class.isAssignableFrom(type)
            || LocalDate.class.isAssignableFrom(type)
            || LocalDateTime.class.isAssignableFrom(type)
            || LocalTime.class.isAssignableFrom(type);
    }

    private static String quoteSql(String raw) {
        String safe = raw == null ? "" : raw.replace("'", "''");
        return "'" + safe + "'";
    }

    private static Optional<String> readGlobalNotDeleteValue() {
        try {
            Environment env = SpringContextHolder.getBean(Environment.class);
            // Try both styles just in case
            String v = env.getProperty("mybatis-plus.global-config.db-config.logic-not-delete-value");
            if (StrUtil.isBlank(v)) {
                v = env.getProperty("mybatis-plus.global-config.dbConfig.logicNotDeleteValue");
            }
            return Optional.ofNullable(v);
        } catch (Exception ignore) {
            return Optional.empty();
        }
    }

    private static String getColumnName(TableInfo tableInfo, Field logicField) {
        if (tableInfo == null || logicField == null) return null;
        String propName = logicField.getName();

        // 1) 主键属性
        String keyProp = tableInfo.getKeyProperty();
        if (propName.equals(keyProp)) {
            return tableInfo.getKeyColumn();
        }

        // 2) 普通字段（通过 TableInfo 映射）
        for (TableFieldInfo fi : tableInfo.getFieldList()) {
            if (propName.equals(fi.getProperty())) {
                return fi.getColumn();
            }
        }

        // 3) 兜底：检查字段自身的 @TableField 注解
        TableField tf = logicField.getAnnotation(TableField.class);
        if (tf != null && StrUtil.isNotBlank(tf.value())) {
            return tf.value();
        }

        // 4) 最后兜底：驼峰转下划线
        return StrUtil.toUnderlineCase(propName);
    }
}

