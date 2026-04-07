package io.github.skyleew.relationmapping.filter;

import cn.hutool.core.util.StrUtil;

import java.util.*;
import java.util.stream.Collectors;

/**
 * 包含规则过滤器：仅允许命中的路径进行 with 预加载，其余一律跳过。
 *
 * 规则语法与 RelationSelector 保持一致：
 * - Owner::path 例如 User::posts 或 User::posts.comments
 * - path         例如 posts（仅在根层匹配）
 */
public class RelationAllowSelector implements RelationFilter {
    private final Set<ParsedRule> includes;

    public RelationAllowSelector(Collection<String> rules) {
        this.includes = normalize(rules);
    }

    /**
     * 返回 true 表示需要跳过；仅当命中允许规则时不跳过
     */
    @Override
    public boolean skip(Class<?> ownerClass, String fieldPath, boolean isRootLevel) {
        if (ownerClass == null || StrUtil.isBlank(fieldPath)) return true; // 默认全部跳过
        String owner = ownerClass.getSimpleName();
        String normPath = normalizePath(fieldPath);

        for (ParsedRule r : includes) {
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
                    return false; // 前缀包含：命中则不跳过
                }
            } else {
                if (rulePath.equalsIgnoreCase(normPath)) {
                    return false; // 精确匹配：不跳过
                }
            }
        }
        return true; // 未命中包含规则：跳过
    }

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

    private static String normalizePath(String p) {
        if (p == null) return "";
        return Arrays.stream(p.split("\\."))
            .map(String::trim)
            .filter(StrUtil::isNotBlank)
            .collect(Collectors.joining("."));
    }
}

