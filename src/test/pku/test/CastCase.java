package test;

import benchmark.internal.Benchmark;

public class CastCase {

    static class A {
        int value;
    }

    public static void main(String[] args) {
        Benchmark.alloc(1);
        Object tmp = new A();
        A casted = (A) tmp;
        Benchmark.test(1, casted);
    }
}
