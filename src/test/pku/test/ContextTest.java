package test;

import benchmark.internal.Benchmark;

public class ContextTest {
    public static class Obj {
        public Obj() {}
    }

    public static Obj f(boolean b) {
        if (b) {
            Benchmark.alloc(1);
            Obj o1 = new Obj();
            return o1;
        } else {
            Benchmark.alloc(2);
            Obj o2 = new Obj();
            return o2;
        }
    }

    public static void main(String[] args) {
        Obj a = f(true);
        Benchmark.test(1, a);
        Obj b = f(false);
        Benchmark.test(2, b);
    }
}
/*
Expected with context-insensitive: 1:1 2, 2:1 2
Expected with context-sensitive (k>=1): 1:1, 2:2
*/