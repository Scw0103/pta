package test;

import benchmark.internal.Benchmark;

public class MultiThreadExceptionCase {

    static class MyException extends Exception {}

    public static void main(String[] args) {
        Thread t = new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    Benchmark.alloc(1);
                    throw new MyException();
                } catch (MyException e) {
                    Benchmark.test(1, e);
                }
            }
        });
        t.start();
        try {
            t.join();
        } catch (InterruptedException e) {
            e.printStackTrace();
        }
    }
}