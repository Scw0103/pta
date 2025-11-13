package test;

import benchmark.internal.Benchmark;

public class CornerCase3 {
    public static class A {
        public A() {}
        public A m() {
            Benchmark.alloc(1);
            return new A();
        }
    }

    public static class B extends A {
        public B() {}
        public A m() {
            Benchmark.alloc(2);
            return new A(); // 返回A对象
        }
    }

    public static class C extends A {
        public C() {}
        public A m() {
            Benchmark.alloc(4);
            return new A(); // 返回A对象
        }
    }

    public static void main(String[] args) {
        Benchmark.alloc(3);
        A a1 = new B(); // alloc 3 for B
        Benchmark.alloc(5);
        A a2 = new C(); // alloc 5 for C
        A result1 = a1.m(); // 调用B.m()
        Benchmark.test(1, result1); // 指向2
        A result2 = a2.m(); // 调用C.m()
        Benchmark.test(2, result2); // 指向4
    }
}
/*
Corner case: 多态调用。
Expected: Andersen分析是流不敏感的，但对于虚调用，会解析所有可能的目标。但在这个例子中，a指向B对象，m()调用B.m()，但由于简单，可能正确。
但如果有多个子类，指向集会合并。
*/