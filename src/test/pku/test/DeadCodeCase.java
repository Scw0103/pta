package test;

import benchmark.internal.Benchmark;
import benchmark.objects.A;
import benchmark.objects.B;

public class DeadCodeCase {
  public static void main(String[] args) {
    Benchmark.alloc(1);
    A a1 = new A();
    
    Benchmark.alloc(2);
    A a2 = new A();

    A a3 = a1;
    A a4 = a1;
    if (args.length < 0) {
      a3 = a2;
    }
    if (2 > 1) {
      a4 = a2;
    }
    Benchmark.test(1, a3);
    Benchmark.test(2, a4);
  }
}
/*
Answer:
  1 : 1
  2 : 2
*/
