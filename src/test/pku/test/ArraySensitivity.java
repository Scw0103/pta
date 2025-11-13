package test;

import benchmark.internal.Benchmark;
import benchmark.objects.A;
import benchmark.objects.B;

public class ArraySensitivity {
    public static void main(String[] args) {
        Benchmark.alloc(1);
        B[] arr1 = new B[10];  // 分配点1
        Benchmark.alloc(2);
        B[] arr2 = new B[10];  // 分配点2
        
        Benchmark.alloc(3);
        B b1 = new B();
        Benchmark.alloc(4);
        B b2 = new B();
        Benchmark.alloc(5);
        B b3 = new B();
        
        arr1[0] = b1;
        arr2[0] = b2;
        arr1[1] = b3;
        
        B x = arr1[0];  // 应该只指向b1
        Benchmark.test(1, x);
        
        B y = arr2[0];  // 应该只指向b2
        Benchmark.test(2, y);
        
        B z = arr1[1];  // 应该只指向b3
        Benchmark.test(3, z);
    }
}

/*
 * 1: 3        // arr1[0] -> b1
 * 2: 4        // arr2[0] -> b2  
 * 3: 5        // arr1[1] -> b3
 * 
 */