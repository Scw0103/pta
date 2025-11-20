package test;

import benchmark.internal.Benchmark;

public class ArrayAliasCase {

    static class B {
    }

    public static void main(String[] args) {
        Benchmark.alloc(1);
        B[] arr1 = new B[10];
        Benchmark.alloc(2);
        arr1[5] = new B();
        B[] arr2 = arr1;

        B a = arr2[5];
        Benchmark.test(1, a);

        B b = arr1[5];
        Benchmark.test(2, b);
    }
}
