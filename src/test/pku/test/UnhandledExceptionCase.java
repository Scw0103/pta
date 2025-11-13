package test;

import benchmark.internal.Benchmark;

public class UnhandledExceptionCase {

    static class MyException extends Exception {}

    public static void main(String[] args) {
        Benchmark.alloc(1);
        throw new RuntimeException("Uncaught Exception");
    }
}