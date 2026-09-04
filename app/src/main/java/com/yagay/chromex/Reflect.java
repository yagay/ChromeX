package com.yagay.chromex;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.List;

final class Reflect {
    private Reflect() {}

    static Class<?> cls(ClassLoader loader, String name) throws ClassNotFoundException {
        return Class.forName(name, false, loader);
    }

    static Field field(Class<?> type, String name) throws NoSuchFieldException {
        Class<?> c = type;
        while (c != null && c != Object.class) {
            try {
                Field f = c.getDeclaredField(name);
                f.setAccessible(true);
                return f;
            } catch (NoSuchFieldException ignored) {
                c = c.getSuperclass();
            }
        }
        throw new NoSuchFieldException(type.getName() + "." + name);
    }

    static Object get(Object target, String name) throws ReflectiveOperationException {
        return field(target.getClass(), name).get(target);
    }

    static long getLong(Object target, String name) throws ReflectiveOperationException {
        return field(target.getClass(), name).getLong(target);
    }

    static int getInt(Object target, String name) throws ReflectiveOperationException {
        return field(target.getClass(), name).getInt(target);
    }

    static boolean getBoolean(Object target, String name) throws ReflectiveOperationException {
        return field(target.getClass(), name).getBoolean(target);
    }

    static void set(Object target, String name, Object value) throws ReflectiveOperationException {
        field(target.getClass(), name).set(target, value);
    }

    static void setBoolean(Object target, String name, boolean value) throws ReflectiveOperationException {
        field(target.getClass(), name).setBoolean(target, value);
    }

    static Method exact(Class<?> type, String name, Class<?>... params) throws NoSuchMethodException {
        Class<?> c = type;
        while (c != null && c != Object.class) {
            try {
                Method m = c.getDeclaredMethod(name, params);
                m.setAccessible(true);
                return m;
            } catch (NoSuchMethodException ignored) {
                c = c.getSuperclass();
            }
        }
        throw new NoSuchMethodException(type.getName() + "#" + name);
    }

    static List<Method> named(Class<?> type, String name) {
        ArrayList<Method> result = new ArrayList<>();
        Class<?> c = type;
        while (c != null && c != Object.class) {
            for (Method m : c.getDeclaredMethods()) {
                if (m.getName().equals(name)) {
                    m.setAccessible(true);
                    result.add(m);
                }
            }
            c = c.getSuperclass();
        }
        return result;
    }

    static Method signature(Class<?> type, Class<?> returnType, Class<?>... params) {
        Method found = null;
        Class<?> c = type;
        while (c != null && c != Object.class) {
            for (Method m : c.getDeclaredMethods()) {
                if (!returnCompatible(returnType, m.getReturnType())) continue;
                Class<?>[] actual = m.getParameterTypes();
                if (actual.length != params.length) continue;
                boolean ok = true;
                for (int i = 0; i < actual.length; i++) {
                    if (!parameterCompatible(params[i], actual[i])) {
                        ok = false;
                        break;
                    }
                }
                if (!ok) continue;
                if (found != null) return null; // ambiguous: fail safe
                m.setAccessible(true);
                found = m;
            }
            c = c.getSuperclass();
        }
        return found;
    }

    static Object call(Object target, String name, Object... args) throws ReflectiveOperationException {
        Method match = findCompatible(target.getClass(), name, args);
        if (match == null) throw new NoSuchMethodException(target.getClass().getName() + "#" + name);
        return match.invoke(target, args);
    }

    static Object callStatic(Class<?> type, String name, Object... args) throws ReflectiveOperationException {
        Method match = findCompatible(type, name, args);
        if (match == null || !Modifier.isStatic(match.getModifiers())) {
            throw new NoSuchMethodException(type.getName() + "#" + name);
        }
        return match.invoke(null, args);
    }

    static Object construct(Class<?> type, Object... args) throws ReflectiveOperationException {
        for (Constructor<?> c : type.getDeclaredConstructors()) {
            Class<?>[] p = c.getParameterTypes();
            if (p.length != args.length) continue;
            boolean ok = true;
            for (int i = 0; i < p.length; i++) {
                if (!valueCompatible(p[i], args[i])) {
                    ok = false;
                    break;
                }
            }
            if (!ok) continue;
            c.setAccessible(true);
            return c.newInstance(args);
        }
        throw new NoSuchMethodException("constructor " + type.getName());
    }

    static Object findFieldValueByType(Object owner, Class<?> wanted) {
        Class<?> c = owner.getClass();
        while (c != null && c != Object.class) {
            for (Field f : c.getDeclaredFields()) {
                if (Modifier.isStatic(f.getModifiers())) continue;
                if (!wanted.isAssignableFrom(f.getType())) continue;
                try {
                    f.setAccessible(true);
                    Object value = f.get(owner);
                    if (value != null) return value;
                } catch (Throwable ignored) {}
            }
            c = c.getSuperclass();
        }
        return null;
    }

    private static Method findCompatible(Class<?> type, String name, Object[] args) {
        Method found = null;
        Class<?> c = type;
        while (c != null && c != Object.class) {
            for (Method m : c.getDeclaredMethods()) {
                if (!m.getName().equals(name)) continue;
                Class<?>[] p = m.getParameterTypes();
                if (p.length != args.length) continue;
                boolean ok = true;
                for (int i = 0; i < p.length; i++) {
                    if (!valueCompatible(p[i], args[i])) {
                        ok = false;
                        break;
                    }
                }
                if (!ok) continue;
                if (found != null) return null;
                m.setAccessible(true);
                found = m;
            }
            c = c.getSuperclass();
        }
        return found;
    }

    private static boolean returnCompatible(Class<?> wanted, Class<?> actual) {
        if (wanted == Object.class) return actual != void.class;
        return box(wanted).isAssignableFrom(box(actual));
    }

    private static boolean parameterCompatible(Class<?> wanted, Class<?> actual) {
        if (wanted == Object.class) return !actual.isPrimitive();
        return box(wanted).isAssignableFrom(box(actual)) || box(actual).isAssignableFrom(box(wanted));
    }

    private static boolean valueCompatible(Class<?> type, Object value) {
        if (value == null) return !type.isPrimitive();
        return box(type).isInstance(value);
    }

    private static Class<?> box(Class<?> c) {
        if (!c.isPrimitive()) return c;
        if (c == boolean.class) return Boolean.class;
        if (c == byte.class) return Byte.class;
        if (c == short.class) return Short.class;
        if (c == int.class) return Integer.class;
        if (c == long.class) return Long.class;
        if (c == float.class) return Float.class;
        if (c == double.class) return Double.class;
        if (c == char.class) return Character.class;
        if (c == void.class) return Void.class;
        return c;
    }
}
