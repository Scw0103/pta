package test;

import benchmark.internal.Benchmark;

public class UnsoundCase {
    public static class A {
        public A() {}
        public A m() {
            return this;
        }
    }

    public static class B extends A {
        public B() {}
        public A m() {
            return this; // 返回this，指向B对象
        }
    }

    public static class C extends A {
        public C() {}
        public A m() {
            return this; // 返回this，指向C对象
        }
    }

    public static void main(String[] args) {
        Benchmark.alloc(1);
        A a1 = new B(); // alloc 1 for B
        Benchmark.alloc(2);
        A a2 = new C(); // alloc 2 for C
        A result1 = a1.m(); // 调用B.m()，返回a1 (对象1)
        Benchmark.test(1, result1); // 应该指向1
        A result2 = a2.m(); // 调用C.m()，返回a2 (对象2)
        Benchmark.test(2, result2); // 应该指向2
    }
}
/*
Expected sound: 1:1, 2:2
If unsound due to thisNode not set: this not pointing, return this points to nothing, so 1:, 2:
*/