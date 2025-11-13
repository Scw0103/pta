package test;

import benchmark.internal.Benchmark;

public class AssignLiteralCase {

    public static void main(String[] args) {
        Benchmark.alloc(1);
        String literal = "Hello, World!";
        Benchmark.test(1, literal);

        Benchmark.alloc(2);
        String anotherLiteral = "Hello, World!";
        Benchmark.test(2, anotherLiteral);
    }
}