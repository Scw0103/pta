package test;

import benchmark.internal.Benchmark;

public class ChainedExceptionCase {

    static class PrimaryException extends Exception {}
    static class SecondaryException extends Exception {}

    public static void main(String[] args) {
        try {
            Benchmark.alloc(1);
            PrimaryException primary = new PrimaryException();
            SecondaryException secondary = new SecondaryException();
            secondary.initCause(primary);
            throw secondary;
        } catch (SecondaryException e) {
            Benchmark.test(1, e);
            Benchmark.test(2, e.getCause());
        }
    }
}