package com.goldenhour;

import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.List;

/**
 * A tiny JUnit-style test runner so the core builds and tests with nothing but a JDK (no Maven Central
 * needed, which also keeps CI and Docker builds fast and reproducible). Any public static void method whose
 * name starts with "test" in the listed classes is executed.
 */
public final class Check {
    private Check() {}

    public static void isTrue(boolean cond, String msg) { if (!cond) throw new AssertionError(msg); }

    public static void near(double expected, double actual, double tol, String msg) {
        if (Math.abs(expected - actual) > tol) throw new AssertionError(msg + " expected " + expected + " but was " + actual);
    }

    public static void equal(Object expected, Object actual, String msg) {
        if (!java.util.Objects.equals(expected, actual)) throw new AssertionError(msg + " expected <" + expected + "> but was <" + actual + ">");
    }

    public static void main(String[] args) throws Exception {
        String[] classes = args.length > 0 ? args : new String[]{
            "com.goldenhour.DataStructuresTest", "com.goldenhour.RoutingTest", "com.goldenhour.DispatchTest", "com.goldenhour.InfraTest", "com.goldenhour.ApiTest"};
        int passed = 0;
        List<String> failed = new ArrayList<>();
        long t0 = System.nanoTime();
        for (String cn : classes) {
            for (Method m : Class.forName(cn).getDeclaredMethods()) {
                if (!m.getName().startsWith("test") || !Modifier.isStatic(m.getModifiers())) continue;
                String id = cn.substring(cn.lastIndexOf('.') + 1) + "." + m.getName();
                long s = System.nanoTime();
                try {
                    m.invoke(null);
                    passed++;
                    System.out.printf("  PASS %-58s %6.0f ms%n", id, (System.nanoTime() - s) / 1e6);
                } catch (java.lang.reflect.InvocationTargetException e) {
                    failed.add(id);
                    System.out.printf("  FAIL %s: %s%n", id, e.getCause());
                    e.getCause().printStackTrace(System.out);
                }
            }
        }
        System.out.printf("%d passed, %d failed in %.1f s%n", passed, failed.size(), (System.nanoTime() - t0) / 1e9);
        System.exit(failed.isEmpty() ? 0 : 1);           // non-daemon server threads must not keep CI alive
    }
}
