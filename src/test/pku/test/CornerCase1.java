package test;

import benchmark.internal.Benchmark;

public class CornerCase1 {
    public static class Obj {
        public Obj() {}
    }

    // 递归超过k=1
    public static Obj f(int n) {
        if (n == 0) {
            Benchmark.alloc(1);
            Obj o = new Obj();
            return o;
        } else {
            return f(n - 1);
        }
    }

    public static void main(String[] args) {
        Obj a = f(2); // f(2) -> f(1) -> f(0)
        Benchmark.test(1, a); // 指向1，但由于k=1，f(2)和f(1)上下文相同，f(0)不同，但返回还是1
    }
}
/*
Corner case: 递归深度超过k。
Expected: 由于k=1，f(2)和f(1)上下文相同（<main, f>），f(0)上下文<main, f, f>，但k=1时push只保留1层，所以f(0)上下文还是<main, f>。
结果：1指向1（正确，但如果k=0也一样）。
*/