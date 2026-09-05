package com.yagay.chromex;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** Immutable result of the extension capability probe. */
public final class ExtensionCapabilityReport {
    public final ExtensionRuntimeMode mode;
    public final List<String> javaHits;
    public final List<String> javaMisses;
    public final List<String> nativeHits;
    public final List<String> nativeMisses;
    public final String nativeLibrary;

    ExtensionCapabilityReport(
            ExtensionRuntimeMode mode,
            List<String> javaHits,
            List<String> javaMisses,
            List<String> nativeHits,
            List<String> nativeMisses,
            String nativeLibrary) {
        this.mode = mode;
        this.javaHits = Collections.unmodifiableList(new ArrayList<>(javaHits));
        this.javaMisses = Collections.unmodifiableList(new ArrayList<>(javaMisses));
        this.nativeHits = Collections.unmodifiableList(new ArrayList<>(nativeHits));
        this.nativeMisses = Collections.unmodifiableList(new ArrayList<>(nativeMisses));
        this.nativeLibrary = nativeLibrary;
    }

    public String toDiagnosticText() {
        StringBuilder out = new StringBuilder();
        out.append("mode=").append(mode).append('\n');
        out.append("nativeLibrary=").append(nativeLibrary == null ? "not-found" : nativeLibrary).append('\n');
        append(out, "JAVA + ", javaHits);
        append(out, "JAVA - ", javaMisses);
        append(out, "NATIVE + ", nativeHits);
        append(out, "NATIVE - ", nativeMisses);
        return out.toString();
    }

    private static void append(StringBuilder out, String prefix, List<String> values) {
        for (String value : values) out.append(prefix).append(value).append('\n');
    }
}
