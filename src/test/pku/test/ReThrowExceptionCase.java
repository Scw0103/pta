package test;

import benchmark.internal.Benchmark;

public class ReThrowExceptionCase {

    static class MyException extends Exception {}

    public static void main(String[] args) {
        try {
            try {
                Benchmark.alloc(1);
                throw new MyException();
            } catch (MyException e) {
                Benchmark.test(1, e);
                throw e;
            }
        } catch (MyException e) {
            Benchmark.test(2, e);
        }
    }
}