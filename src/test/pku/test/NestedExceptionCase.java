package test;

import benchmark.internal.Benchmark;

public class NestedExceptionCase {

    static class InnerException extends Exception {}
    static class OuterException extends Exception {}

    public static void main(String[] args) {
        try {
            try {
                Benchmark.alloc(1);
                throw new InnerException();
            } catch (InnerException e) {
                Benchmark.test(1, e);
                throw new OuterException();
            }
        } catch (OuterException e) {
            Benchmark.test(2, e);
        }
    }
}