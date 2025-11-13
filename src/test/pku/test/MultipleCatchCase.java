package test;

import benchmark.internal.Benchmark;

public class MultipleCatchCase {

    static class FirstException extends Exception {}
    static class SecondException extends Exception {}

    public static void main(String[] args) {
        try {
            Benchmark.alloc(1);
            throw new SecondException();
        } catch (Exception e) {
            if (e instanceof FirstException) {
                Benchmark.test(1, e);
            } else if (e instanceof SecondException) {
                Benchmark.test(2, e);
            }
        }
    }
}