package com.yagay.chromex;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/** Reads one constant JNI selector from the caller's own DEX instead of hard-coding R8 J.N ids. */
final class DexNativeSelectorResolver {
    private DexNativeSelectorResolver() {}

    static Integer resolve(String codePath, String callerDescriptor, String targetDescriptor) {
        if (codePath == null || callerDescriptor == null || targetDescriptor == null) return null;
        try {
            File file = new File(codePath);
            if (!file.isFile()) return null;
            if (codePath.endsWith(".dex")) {
                try (InputStream in = new FileInputStream(file)) {
                    return fromDex(readAll(in), callerDescriptor, targetDescriptor);
                }
            }
            try (ZipFile zip = new ZipFile(file)) {
                java.util.Enumeration<? extends ZipEntry> entries = zip.entries();
                while (entries.hasMoreElements()) {
                    ZipEntry entry = entries.nextElement();
                    String name = entry.getName();
                    if (!name.matches("classes(?:\\d+)?\\.dex")) continue;
                    try (InputStream in = zip.getInputStream(entry)) {
                        Integer value = fromDex(readAll(in), callerDescriptor, targetDescriptor);
                        if (value != null) return value;
                    }
                }
            }
        } catch (Throwable ignored) {}
        return null;
    }

    private static Integer fromDex(byte[] dex, String callerDescriptor, String targetDescriptor) {
        if (dex == null || dex.length < 112) return null;
        try {
            DexTable table = new DexTable(dex);
            int caller = table.findMethod(callerDescriptor);
            int target = table.findMethod(targetDescriptor);
            if (caller < 0 || target < 0) return null;
            int codeOff = table.findCodeOffset(caller);
            if (codeOff <= 0 || codeOff + 16 > dex.length) return null;
            int units = u4(dex, codeOff + 12);
            int start = codeOff + 16;
            if (units <= 0 || start + units * 2L > dex.length) return null;
            for (int i = 0; i + 2 < units; i++) {
                int first = u2(dex, start + i * 2);
                int opcode = first & 0xff;
                if (opcode != 0x71 && opcode != 0x77) continue; // invoke-static / invoke-static-range
                int methodIndex = u2(dex, start + (i + 1) * 2);
                if (methodIndex != target) continue;
                int register = opcode == 0x77
                        ? u2(dex, start + (i + 2) * 2)
                        : (u2(dex, start + (i + 2) * 2) & 0x0f);
                Integer selector = nearestConst(dex, start, i, register);
                if (selector != null && selector >= 0 && selector <= 65535) return selector;
            }
        } catch (Throwable ignored) {}
        return null;
    }

    private static Integer nearestConst(byte[] dex, int start, int invokeUnit, int register) {
        int from = Math.max(0, invokeUnit - 24);
        for (int i = invokeUnit - 1; i >= from; i--) {
            int unit = u2(dex, start + i * 2);
            int op = unit & 0xff;
            if (op == 0x12) { // const/4
                int a = (unit >>> 8) & 0x0f;
                if (a != register) continue;
                int lit = (unit >>> 12) & 0x0f;
                if ((lit & 0x8) != 0) lit -= 16;
                return lit;
            }
            if (op == 0x13 && i + 1 < invokeUnit) { // const/16
                int a = (unit >>> 8) & 0xff;
                if (a == register) return (int) (short) u2(dex, start + (i + 1) * 2);
            }
            if (op == 0x14 && i + 2 < invokeUnit) { // const
                int a = (unit >>> 8) & 0xff;
                if (a == register) {
                    return u2(dex, start + (i + 1) * 2)
                            | (u2(dex, start + (i + 2) * 2) << 16);
                }
            }
            if (op == 0x15 && i + 1 < invokeUnit) { // const/high16
                int a = (unit >>> 8) & 0xff;
                if (a == register) return ((short) u2(dex, start + (i + 1) * 2)) << 16;
            }
        }
        return null;
    }

    private static byte[] readAll(InputStream in) throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buffer = new byte[64 * 1024];
        int read;
        while ((read = in.read(buffer)) >= 0) out.write(buffer, 0, read);
        return out.toByteArray();
    }

    private static int u2(byte[] b, int o) {
        return (b[o] & 0xff) | ((b[o + 1] & 0xff) << 8);
    }

    private static int u4(byte[] b, int o) {
        return u2(b, o) | (u2(b, o + 2) << 16);
    }

    private static int[] uleb(byte[] b, int off) {
        int value = 0;
        int shift = 0;
        int cursor = off;
        while (cursor < b.length) {
            int x = b[cursor++] & 0xff;
            value |= (x & 0x7f) << shift;
            if ((x & 0x80) == 0) return new int[]{value, cursor};
            shift += 7;
        }
        return new int[]{0, cursor};
    }

    private static final class DexTable {
        final byte[] b;
        final String[] strings;
        final String[] types;
        final Proto[] protos;
        final MethodId[] methods;

        DexTable(byte[] b) {
            this.b = b;
            int stringSize = u4(b, 56), stringOff = u4(b, 60);
            strings = new String[stringSize];
            for (int i = 0; i < stringSize; i++) strings[i] = readString(u4(b, stringOff + i * 4));
            int typeSize = u4(b, 64), typeOff = u4(b, 68);
            types = new String[typeSize];
            for (int i = 0; i < typeSize; i++) types[i] = strings[u4(b, typeOff + i * 4)];
            int protoSize = u4(b, 72), protoOff = u4(b, 76);
            protos = new Proto[protoSize];
            for (int i = 0; i < protoSize; i++) {
                int o = protoOff + i * 12;
                int returnIdx = u4(b, o + 4);
                int paramsOff = u4(b, o + 8);
                StringBuilder params = new StringBuilder();
                if (paramsOff != 0) {
                    int count = u4(b, paramsOff);
                    for (int j = 0; j < count; j++) params.append(types[u2(b, paramsOff + 4 + j * 2)]);
                }
                protos[i] = new Proto(params.toString(), types[returnIdx]);
            }
            int methodSize = u4(b, 88), methodOff = u4(b, 92);
            methods = new MethodId[methodSize];
            for (int i = 0; i < methodSize; i++) {
                int o = methodOff + i * 8;
                methods[i] = new MethodId(types[u2(b, o)], protos[u2(b, o + 2)], strings[u4(b, o + 4)]);
            }
        }

        int findMethod(String descriptor) {
            for (int i = 0; i < methods.length; i++) {
                if (methods[i].descriptor().equals(descriptor)) return i;
            }
            return -1;
        }

        int findCodeOffset(int wanted) {
            int classSize = u4(b, 96), classOff = u4(b, 100);
            for (int i = 0; i < classSize; i++) {
                int dataOff = u4(b, classOff + i * 32 + 24);
                if (dataOff == 0) continue;
                int[] a = uleb(b, dataOff); int staticFields = a[0], p = a[1];
                a = uleb(b, p); int instanceFields = a[0]; p = a[1];
                a = uleb(b, p); int direct = a[0]; p = a[1];
                a = uleb(b, p); int virtual = a[0]; p = a[1];
                for (int f = 0; f < staticFields + instanceFields; f++) {
                    a = uleb(b, p); p = a[1]; a = uleb(b, p); p = a[1];
                }
                int index = 0;
                for (int m = 0; m < direct; m++) {
                    a = uleb(b, p); index += a[0]; p = a[1];
                    a = uleb(b, p); p = a[1];
                    a = uleb(b, p); int code = a[0]; p = a[1];
                    if (index == wanted) return code;
                }
                index = 0;
                for (int m = 0; m < virtual; m++) {
                    a = uleb(b, p); index += a[0]; p = a[1];
                    a = uleb(b, p); p = a[1];
                    a = uleb(b, p); int code = a[0]; p = a[1];
                    if (index == wanted) return code;
                }
            }
            return -1;
        }

        String readString(int off) {
            int[] len = uleb(b, off);
            int start = len[1], end = start;
            while (end < b.length && b[end] != 0) end++;
            return new String(b, start, end - start, StandardCharsets.UTF_8);
        }
    }

    private static final class Proto {
        final String params, result;
        Proto(String params, String result) { this.params = params; this.result = result; }
    }

    private static final class MethodId {
        final String owner, name;
        final Proto proto;
        MethodId(String owner, Proto proto, String name) {
            this.owner = owner;
            this.proto = proto;
            this.name = name;
        }
        String descriptor() { return owner + "->" + name + '(' + proto.params + ')' + proto.result; }
    }
}
