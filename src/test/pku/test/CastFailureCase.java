package test;

import benchmark.internal.Benchmark;

public class CastFailureCase {

    public static void main(String[] args) {
        Object obj = new String("Test");
        try {
            Benchmark.alloc(1);
            Integer num = (Integer) obj; // This will throw ClassCastException
        } catch (ClassCastException e) {
            Benchmark.test(1, e);
        }
    }
}