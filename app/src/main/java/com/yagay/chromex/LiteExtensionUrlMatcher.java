package com.yagay.chromex;

import java.net.URI;
import java.util.List;
import java.util.regex.Pattern;

final class LiteExtensionUrlMatcher {
    private LiteExtensionUrlMatcher() {}

    static boolean matchesAny(String url, List<String> includes, List<String> excludes) {
        if (url == null || includes == null || includes.isEmpty()) return false;
        boolean included = false;
        for (String pattern : includes) {
            if (matches(url, pattern)) {
                included = true;
                break;
            }
        }
        if (!included) return false;
        if (excludes != null) {
            for (String pattern : excludes) if (matches(url, pattern)) return false;
        }
        return true;
    }

    static boolean matches(String url, String matchPattern) {
        if (url == null || matchPattern == null || matchPattern.isBlank()) return false;
        if ("<all_urls>".equals(matchPattern)) {
            return url.startsWith("http://") || url.startsWith("https://")
                    || url.startsWith("file://") || url.startsWith("ftp://");
        }
        try {
            URI uri = URI.create(url);
            int schemeSep = matchPattern.indexOf("://");
            if (schemeSep <= 0) return false;
            String schemePattern = matchPattern.substring(0, schemeSep);
            String rest = matchPattern.substring(schemeSep + 3);
            int slash = rest.indexOf('/');
            String hostPattern = slash < 0 ? rest : rest.substring(0, slash);
            String pathPattern = slash < 0 ? "/" : rest.substring(slash);

            String scheme = uri.getScheme() == null ? "" : uri.getScheme();
            if (!"*".equals(schemePattern) && !schemePattern.equalsIgnoreCase(scheme)) return false;
            if ("*".equals(schemePattern)
                    && !("http".equalsIgnoreCase(scheme) || "https".equalsIgnoreCase(scheme))) {
                return false;
            }

            String host = uri.getHost();
            if (host == null) host = "";
            if (!hostMatches(host.toLowerCase(), hostPattern.toLowerCase())) return false;

            String path = uri.getRawPath();
            if (path == null || path.isEmpty()) path = "/";
            String query = uri.getRawQuery();
            if (query != null) path += "?" + query;
            return wildcard(pathPattern).matcher(path).matches();
        } catch (Throwable ignored) {
            return false;
        }
    }

    private static boolean hostMatches(String host, String pattern) {
        if ("*".equals(pattern)) return !host.isEmpty();
        if (pattern.startsWith("*.")) {
            String base = pattern.substring(2);
            return host.equals(base) || host.endsWith("." + base);
        }
        return host.equals(pattern);
    }

    private static Pattern wildcard(String value) {
        StringBuilder regex = new StringBuilder("^");
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            if (c == '*') regex.append(".*");
            else {
                if ("\\.^$|?+()[]{}".indexOf(c) >= 0) regex.append('\\');
                regex.append(c);
            }
        }
        return Pattern.compile(regex.append('$').toString());
    }
}
