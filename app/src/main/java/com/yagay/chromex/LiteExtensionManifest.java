package com.yagay.chromex;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

final class LiteExtensionManifest {
    static final class ContentScript {
        final List<String> matches;
        final List<String> excludeMatches;
        final List<String> js;
        final List<String> css;
        final String runAt;
        final boolean allFrames;

        ContentScript(List<String> matches, List<String> excludeMatches, List<String> js,
                      List<String> css, String runAt, boolean allFrames) {
            this.matches = Collections.unmodifiableList(new ArrayList<>(matches));
            this.excludeMatches = Collections.unmodifiableList(new ArrayList<>(excludeMatches));
            this.js = Collections.unmodifiableList(new ArrayList<>(js));
            this.css = Collections.unmodifiableList(new ArrayList<>(css));
            this.runAt = runAt;
            this.allFrames = allFrames;
        }
    }

    final String id;
    final String name;
    final String version;
    final int manifestVersion;
    final List<ContentScript> contentScripts;

    LiteExtensionManifest(String id, String name, String version, int manifestVersion,
                          List<ContentScript> contentScripts) {
        this.id = id;
        this.name = name;
        this.version = version;
        this.manifestVersion = manifestVersion;
        this.contentScripts = Collections.unmodifiableList(new ArrayList<>(contentScripts));
    }

    static LiteExtensionManifest parse(String id, String json) throws Exception {
        JSONObject root = new JSONObject(json);
        String name = root.optString("name", id);
        String version = root.optString("version", "0");
        int mv = root.optInt("manifest_version", 0);
        ArrayList<ContentScript> scripts = new ArrayList<>();
        JSONArray array = root.optJSONArray("content_scripts");
        if (array != null) {
            for (int i = 0; i < array.length(); i++) {
                JSONObject item = array.optJSONObject(i);
                if (item == null) continue;
                List<String> matches = strings(item.optJSONArray("matches"));
                if (matches.isEmpty()) continue;
                scripts.add(new ContentScript(
                        matches,
                        strings(item.optJSONArray("exclude_matches")),
                        strings(item.optJSONArray("js")),
                        strings(item.optJSONArray("css")),
                        item.optString("run_at", "document_idle"),
                        item.optBoolean("all_frames", false)));
            }
        }
        return new LiteExtensionManifest(id, name, version, mv, scripts);
    }

    private static List<String> strings(JSONArray array) {
        if (array == null) return Collections.emptyList();
        ArrayList<String> out = new ArrayList<>();
        for (int i = 0; i < array.length(); i++) {
            String value = array.optString(i, null);
            if (value != null && !value.isBlank()) out.add(value);
        }
        return out;
    }
}
