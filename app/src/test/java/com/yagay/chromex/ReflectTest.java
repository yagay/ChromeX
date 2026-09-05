package com.yagay.chromex;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;

import java.lang.reflect.Method;

import org.junit.Test;

public class ReflectTest {
    static class Parent {
        public String choose(boolean value) { return "parent"; }
    }

    static class Child extends Parent {
        @Override
        public String choose(boolean value) { return "child"; }
    }

    @Test
    public void signaturePrefersMostDerivedOverride() throws Exception {
        Method method = Reflect.signature(Child.class, String.class, boolean.class);
        assertNotNull(method);
        assertEquals(Child.class, method.getDeclaringClass());
        assertEquals("child", method.invoke(new Child(), true));
    }

    @Test
    public void compatibleCallPrefersMostDerivedOverride() throws Exception {
        assertEquals("child", Reflect.call(new Child(), "choose", Boolean.TRUE));
    }
}
