package io.github.skyleew.relationmapping.filter;

import lombok.Value;
import java.util.List;

/**
 * 解析后的规则
 * - ownerSimpleName: 类名（可为空）
 * - pathSegments: 路径分段，如 ["posts", "comments"]
 * - prefix: 是否为前缀匹配（末尾使用通配符 * 或 **，表示“匹配该路径及其所有下级”）
 */
@Value
public class ParsedRule {
    String ownerSimpleName;
    List<String> pathSegments;
    boolean prefix;
}

