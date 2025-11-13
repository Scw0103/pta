package test;

import benchmark.internal.Benchmark;

public class LiteralPropagationCase {

    public static void main(String[] args) {
        Benchmark.alloc(1);
        String literal = "Hello, World!";
        Benchmark.test(1, literal);

        Benchmark.alloc(2);
        String anotherLiteral = literal;
        Benchmark.test(2, anotherLiteral);

        Benchmark.alloc(3);
        String thirdLiteral = anotherLiteral;
        Benchmark.test(3, thirdLiteral);
    }
}