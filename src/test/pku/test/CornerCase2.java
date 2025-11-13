package test;

import benchmark.internal.Benchmark;

public class CornerCase2 {
    public static class Obj {
        public Obj() {}
    }

    public static void main(String[] args) {
        Obj[] arr = new Obj[2];
        Benchmark.alloc(1);
        Obj o1 = new Obj();
        Benchmark.alloc(2);
        Obj o2 = new Obj();
        arr[0] = o1;
        arr[1] = o2;
        Benchmark.test(1, arr[0]); // 指向1
        Benchmark.test(2, arr[1]); // 指向2
        // 但由于数组field-insensitive，arr指向{1,2}，所以arr[0]和arr[1]都指向{1,2}
    }
}
/*
Corner case: 数组元素别名。
Expected: 由于数组按field-insensitive处理，arr指向所有存储的对象，所以arr[0]和arr[1]都指向{1,2}，无法精确区分。
*/