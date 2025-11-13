package test;

import benchmark.internal.Benchmark;

public class ExceptionCase {

    static class MyException extends Exception {
    }

    public static void main(String[] args) {
        try {
            Benchmark.alloc(2);
            MyException e = new MyException();
            throw e;
        } catch (MyException caught) {
            Benchmark.test(2, caught);
        }
    }
}
