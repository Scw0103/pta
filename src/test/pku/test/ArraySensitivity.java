package test;

import benchmark.internal.Benchmark;
import benchmark.objects.A;
import benchmark.objects.B;

public class ArraySensitivity {
    public static void main(String[] args) {
        Benchmark.alloc(1);
        B[] arr1 = new B[10];  // 分配点1

        Benchmark.alloc(2);
        arr1[5] = new B();  // 分配点2
        B[] arr2 = arr1;

        Benchmark.alloc(3);
        arr2[3] = new B();  // 分配点3

        B a = arr2[5]; 
        Benchmark.test(1, a);

        B b = arr1[5];
        Benchmark.test(2, b);

        B c = arr1[3];
        Benchmark.test(3, c);

        B d = arr1[args.length];
        Benchmark.test(4, d);
    }
}

/*
 * 1: 2
 * 2: 2
 * 3: 3
 * 4: 2 3
 */