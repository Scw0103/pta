package test;

import benchmark.internal.Benchmark;

public class ChainedTypeCastCase {

    public static void main(String[] args) {
        Object obj = new String("Test");
        try {
            Benchmark.alloc(1);
            Integer num = (Integer) obj; // First cast, will throw ClassCastException
        } catch (ClassCastException e) {
            Benchmark.test(1, e);
        }

        try {
            Benchmark.alloc(2);
            Double dbl = (Double) obj; // Second cast, will throw ClassCastException
        } catch (ClassCastException e) {
            Benchmark.test(2, e);
        }
    }
}