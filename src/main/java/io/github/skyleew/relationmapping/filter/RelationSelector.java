package io.github.skyleew.relationmapping.filter;

import cn.hutool.core.util.StrUtil;

import java.util.*;
import java.util.stream.Collectors;

/**
 * 基于规则字符串的关系过滤器实现。
 * 规则语法：
 * - Owner::path 例如 User::posts 或 User::posts.comments
 * - path         例如 posts（仅在根层匹配）
 */
public class RelationSelector implements RelationFilter {
    private final Set<ParsedRule> excludes;

    /**
     * @param rules 排除规则集合
     */
    public RelationSelector(Collection<String> rules) {
        this.excludes = normalize(rules);
    }

    /**
     * 判断是否跳过当前路径
     */
    @Override
    public boolean skip(Class<?> ownerClass, String fieldPath, boolean isRootLevel) {
        if (ownerClass == null || StrUtil.isBlank(fieldPath)) return false;
        String owner = ownerClass.getSimpleName();
        String normPath = normalizePath(fieldPath);

        for (ParsedRule r : excludes) {
            boolean ownerMatch;
            if (r.getOwnerSimpleName() == null) {
                // 无 Owner 的规则：
                // - 单段（如 "posts"）仅在根层匹配
                // - 多段（如 "posts.comments"）或带前缀通配（如 "posts.*"/"posts.**"）允许在任意层匹配
                boolean ruleHasNested = r.getPathSegments() != null && r.getPathSegments().size() > 1;
                ownerMatch = r.isPrefix() || ruleHasNested || isRootLevel;
            } else {
                ownerMatch = r.getOwnerSimpleName().equalsIgnoreCase(owner);
            }
            if (!ownerMatch) continue;

            String rulePath = String.join(".", r.getPathSegments());
            if (r.isPrefix()) {
                String rp = rulePath.toLowerCase();
                String np = normPath.toLowerCase();
                if (np.equals(rp) || np.startsWith(rp + ".")) {
                    return true; // 前缀排除：命中则跳过
                }
            } else {
                if (rulePath.equalsIgnoreCase(normPath)) {
                    return true; // 精确排除：命中则跳过
                }
            }
        }
        return false;
    }

    /**
     * 解析与去重规则
     */
    private static Set<ParsedRule> normalize(Collection<String> rules) {
        if (rules == null) return Collections.emptySet();
        Set<String> seen = new HashSet<>();
        Set<ParsedRule> out = new LinkedHashSet<>();
        for (String raw : rules) {
            if (StrUtil.isBlank(raw)) continue;
            String s = raw.trim();
            String owner = null;
            String path = s;
            int idx = s.indexOf("::");
            if (idx > 0) {
                owner = s.substring(0, idx).trim();
                path = s.substring(idx + 2).trim();
            }
            // 解析分段并识别是否有前缀通配（末尾 * 或 **）
            List<String> rawSegs = Arrays.stream(path.split("\\."))
                .map(String::trim)
                .filter(StrUtil::isNotBlank)
                .collect(Collectors.toList());
            if (rawSegs.isEmpty()) continue;

            boolean prefix = false;
            if (!rawSegs.isEmpty()) {
                String last = rawSegs.get(rawSegs.size() - 1);
                if ("*".equals(last) || "**".equals(last)) {
                    prefix = true;
                    rawSegs = rawSegs.subList(0, rawSegs.size() - 1);
                }
            }
            List<String> segs = rawSegs;
            String normKey = (owner == null ? "" : owner.toLowerCase() + "::")
                + normalizePath(String.join(".", segs)).toLowerCase()
                + (prefix ? "/prefix" : "");
            if (!seen.add(normKey)) continue;

            out.add(new ParsedRule(owner, segs, prefix));
        }
        return out;
    }

    /**
     * 统一路径字符串（去多余空白）
     */
    private static String normalizePath(String p) {
        if (p == null) return "";
        return Arrays.stream(p.split("\\."))
            .map(String::trim)
            .filter(StrUtil::isNotBlank)
            .collect(Collectors.joining("."));
    }
}

