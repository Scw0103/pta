package test;

import benchmark.internal.Benchmark;

public class ContextTest2 {
    public static class A {
        public A() {}
        public A f() {
            return this;
        }
    }

    public static void main(String[] args) {
        Benchmark.alloc(1);
        A a1 = new A();
        Benchmark.alloc(2);
        A a2 = new A();
        A b1 = a1.f();
        Benchmark.test(1, b1);
        A b2 = a2.f();
        Benchmark.test(2, b2);
    }
}
/*
Expected with context-insensitive: 1:1 2, 2:1 2
Expected with context-sensitive: 1:1, 2:2
*/