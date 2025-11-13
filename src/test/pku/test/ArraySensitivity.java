package test;

import benchmark.internal.Benchmark;
import benchmark.objects.A;
import benchmark.objects.B;

public class ArraySensitivity {
    public static void main(String[] args) {
        Benchmark.alloc(1);
        B[] arr1 = new B[10];  // 分配点1
        // B x = new B();
        Benchmark.alloc(2);
        arr1[5] = new B();  // 分配点2
        B[] arr2 = arr1;

        B a = arr2[5]; 
        Benchmark.test(1, a);

        B b = arr1[5];
        Benchmark.test(2, b);
    }
}

/*
 * 1: 3        // arr1[0] -> b1
 * 2: 4        // arr2[0] -> b2  
 * 3: 5        // arr1[1] -> b3
 * 
 */