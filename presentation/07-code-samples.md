# Code Samples Reference

This file contains all the code samples used in the presentation for easy copy-paste.

---

## Lamport's Circular Buffer Implementation

### Correct Implementation

```java
/**
 * Lamport's Circular Buffer - Lock-free SPSC Queue
 * Single-Producer, Single-Consumer design
 * 
 * @param <E> Element type
 */
public class LamportBuffer<E> {
    private final E[] buffer;
    private final int capacity;
    
    // Volatile ensures visibility across threads
    private volatile int head = 0;  // Consumer reads from here
    private volatile int tail = 0;  // Producer writes here
    
    @SuppressWarnings("unchecked")
    public LamportBuffer(int capacity) {
        if (capacity < 2) {
            throw new IllegalArgumentException("Capacity must be at least 2");
        }
        this.capacity = capacity;
        this.buffer = (E[]) new Object[capacity];
    }
    
    /**
     * Producer: Add element to buffer
     * Must be called from a single producer thread only
     * 
     * @param element the element to add (can be null)
     * @return true if successful, false if buffer is full
     */
    public boolean offer(E element) {
        int currentTail = tail;
        int nextTail = (currentTail + 1) % capacity;
        
        if (nextTail == head) {
            return false; // Buffer is full
        }
        
        buffer[currentTail] = element;
        tail = nextTail; // Volatile write - publishes the element
        return true;
    }
    
    /**
     * Consumer: Remove element from buffer
     * Must be called from a single consumer thread only
     * 
     * @return element if available, null if buffer is empty
     */
    public E poll() {
        int currentHead = head;
        
        if (currentHead == tail) {
            return null; // Buffer is empty
        }
        
        E element = buffer[currentHead];
        buffer[currentHead] = null; // Help GC
        head = (currentHead + 1) % capacity; // Volatile write
        return element;
    }
    
    /**
     * Check if buffer is empty
     * @return true if empty
     */
    public boolean isEmpty() {
        return head == tail;
    }
    
    /**
     * Get current number of elements
     * Note: This is approximate in concurrent context
     * @return number of elements
     */
    public int size() {
        int h = head;
        int t = tail;
        int diff = t - h;
        return diff >= 0 ? diff : diff + capacity;
    }
    
    /**
     * Get effective capacity (capacity - 1 due to sentinel slot)
     * @return maximum number of elements
     */
    public int getEffectiveCapacity() {
        return capacity - 1;
    }
}
```

### Broken Implementation (for testing)

```java
/**
 * BROKEN Implementation - Missing volatile!
 * Used to demonstrate jcstress detecting visibility bugs
 */
public class BrokenLamportBuffer<E> {
    private final E[] buffer;
    private final int capacity;
    
    // BUG: These should be volatile!
    private int head = 0;
    private int tail = 0;
    
    @SuppressWarnings("unchecked")
    public BrokenLamportBuffer(int capacity) {
        this.capacity = capacity;
        this.buffer = (E[]) new Object[capacity];
    }
    
    public boolean offer(E element) {
        int currentTail = tail;
        int nextTail = (currentTail + 1) % capacity;
        
        if (nextTail == head) {
            return false;
        }
        
        buffer[currentTail] = element;
        tail = nextTail;
        return true;
    }
    
    public E poll() {
        int currentHead = head;
        
        if (currentHead == tail) {
            return null;
        }
        
        E element = buffer[currentHead];
        buffer[currentHead] = null;
        head = (currentHead + 1) % capacity;
        return element;
    }
}
```

### Synchronized Baseline

```java
/**
 * Synchronized Queue for performance comparison
 * Uses synchronized methods - full mutual exclusion
 */
public class SynchronizedQueue<E> {
    private final Object[] buffer;
    private int head = 0;
    private int tail = 0;
    private final int capacity;
    
    public SynchronizedQueue(int capacity) {
        this.capacity = capacity;
        this.buffer = new Object[capacity];
    }
    
    public synchronized boolean offer(E element) {
        int nextTail = (tail + 1) % capacity;
        if (nextTail == head) {
            return false;
        }
        buffer[tail] = element;
        tail = nextTail;
        return true;
    }
    
    @SuppressWarnings("unchecked")
    public synchronized E poll() {
        if (head == tail) {
            return null;
        }
        E element = (E) buffer[head];
        buffer[head] = null;
        head = (head + 1) % capacity;
        return element;
    }
    
    public synchronized boolean isEmpty() {
        return head == tail;
    }
    
    public synchronized int size() {
        int diff = tail - head;
        return diff >= 0 ? diff : diff + capacity;
    }
}
```

---

## JUnit Tests

```java
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class LamportBufferUnitTest {
    
    private static final int CAPACITY = 8;
    private LamportBuffer<Integer> buffer;
    
    @BeforeEach
    void setUp() {
        buffer = new LamportBuffer<>(CAPACITY);
    }
    
    @Test
    @DisplayName("Offer and poll single element")
    void testBasicOfferAndPoll() {
        Integer element = 42;
        boolean offered = buffer.offer(element);
        Integer polled = buffer.poll();
        
        assertTrue(offered);
        assertEquals(element, polled);
    }
    
    @Test
    @DisplayName("New buffer should be empty")
    void testNewBufferIsEmpty() {
        assertTrue(buffer.isEmpty());
        assertEquals(0, buffer.size());
    }
    
    @Test
    @DisplayName("Poll on empty buffer returns null")
    void testPollOnEmpty() {
        assertNull(buffer.poll());
        assertTrue(buffer.isEmpty());
    }
    
    @Test
    @DisplayName("Offer on full buffer returns false")
    void testOfferOnFull() {
        for (int i = 0; i < CAPACITY - 1; i++) {
            assertTrue(buffer.offer(i));
        }
        assertFalse(buffer.offer(999));
        assertEquals(CAPACITY - 1, buffer.size());
    }
    
    @Test
    @DisplayName("Elements maintain FIFO order")
    void testFifoOrder() {
        List<Integer> input = List.of(1, 2, 3, 4, 5);
        input.forEach(buffer::offer);
        
        List<Integer> output = new ArrayList<>();
        Integer element;
        while ((element = buffer.poll()) != null) {
            output.add(element);
        }
        
        assertEquals(input, output);
    }
    
    @Test
    @DisplayName("Buffer handles wrap-around correctly")
    void testWrapAround() {
        for (int round = 0; round < 3; round++) {
            for (int i = 0; i < CAPACITY - 1; i++) {
                assertTrue(buffer.offer(round * 100 + i));
            }
            for (int i = 0; i < CAPACITY - 1; i++) {
                assertEquals(round * 100 + i, buffer.poll());
            }
            assertTrue(buffer.isEmpty());
        }
    }
}
```

---

## jcstress Tests

### Maven Dependency

```xml
<dependency>
    <groupId>org.openjdk.jcstress</groupId>
    <artifactId>jcstress-core</artifactId>
    <version>0.16</version>
</dependency>
```

### Test Classes

```java
import org.openjdk.jcstress.annotations.*;
import org.openjdk.jcstress.infra.results.I_Result;
import org.openjdk.jcstress.infra.results.II_Result;

/**
 * Basic producer-consumer test
 */
@JCStressTest
@Outcome(id = "42", expect = Expect.ACCEPTABLE, desc = "Consumer saw the value")
@Outcome(id = "-1", expect = Expect.ACCEPTABLE, desc = "Consumer ran first, buffer empty")
@Outcome(id = "0", expect = Expect.FORBIDDEN, desc = "Visibility bug - saw partial state")
@State
public class LamportBufferSingleItem {
    
    private final LamportBuffer<Integer> buffer = new LamportBuffer<>(4);
    
    @Actor
    public void producer() {
        buffer.offer(42);
    }
    
    @Actor
    public void consumer(I_Result r) {
        Integer result = buffer.poll();
        r.r1 = (result == null) ? -1 : result;
    }
}

/**
 * FIFO order test
 */
@JCStressTest
@Outcome(id = "1, 2", expect = Expect.ACCEPTABLE, desc = "Both consumed in order")
@Outcome(id = "1, -1", expect = Expect.ACCEPTABLE, desc = "Only first consumed")
@Outcome(id = "-1, -1", expect = Expect.ACCEPTABLE, desc = "Nothing consumed yet")
@Outcome(id = "-1, 2", expect = Expect.FORBIDDEN, desc = "Order violated!")
@Outcome(id = "2, 1", expect = Expect.FORBIDDEN, desc = "Wrong order!")
@Outcome(id = "2, 2", expect = Expect.FORBIDDEN, desc = "Duplicate read!")
@State
public class LamportBufferFifoOrder {
    
    private final LamportBuffer<Integer> buffer = new LamportBuffer<>(4);
    
    @Actor
    public void producer() {
        buffer.offer(1);
        buffer.offer(2);
    }
    
    @Actor
    public void consumer(II_Result r) {
        Integer first = buffer.poll();
        Integer second = buffer.poll();
        r.r1 = (first == null) ? -1 : first;
        r.r2 = (second == null) ? -1 : second;
    }
}

/**
 * No lost elements test with arbiter
 */
@JCStressTest
@Outcome(id = "3", expect = Expect.ACCEPTABLE, desc = "All elements consumed")
@State
public class LamportBufferNoLoss {
    
    private final LamportBuffer<Integer> buffer = new LamportBuffer<>(8);
    
    @Actor
    public void producer() {
        buffer.offer(1);
        buffer.offer(2);
        buffer.offer(3);
    }
    
    @Arbiter
    public void arbiter(I_Result r) {
        int count = 0;
        while (buffer.poll() != null) {
            count++;
        }
        r.r1 = count;
    }
}

/**
 * Test for broken (non-volatile) implementation
 */
@JCStressTest
@Outcome(id = "42", expect = Expect.ACCEPTABLE, desc = "Correct")
@Outcome(id = "-1", expect = Expect.ACCEPTABLE, desc = "Not yet visible")
@Outcome(id = "0", expect = Expect.ACCEPTABLE_INTERESTING, 
         desc = "Saw stale data - visibility bug!")
@State
public class BrokenBufferVisibility {
    
    private final BrokenLamportBuffer<Integer> buffer = 
        new BrokenLamportBuffer<>(4);
    
    @Actor
    public void producer() {
        buffer.offer(42);
    }
    
    @Actor
    public void consumer(I_Result r) {
        Integer result = buffer.poll();
        r.r1 = (result == null) ? -1 : result;
    }
}
```

---

## Fray Tests

### Maven Dependency

```xml
<dependency>
    <groupId>org.pastalab</groupId>
    <artifactId>fray-junit</artifactId>
    <version>0.2.2</version>
    <scope>test</scope>
</dependency>
```

### Test Classes

```java
import org.pastalab.fray.junit.annotations.ConcurrentTest;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

public class LamportBufferFrayTest {
    
    @ConcurrentTest(iterations = 1000)
    public void testProducerConsumer() throws InterruptedException {
        LamportBuffer<Integer> buffer = new LamportBuffer<>(4);
        AtomicReference<Integer> consumed = new AtomicReference<>();
        
        Thread producer = new Thread(() -> buffer.offer(42));
        Thread consumer = new Thread(() -> consumed.set(buffer.poll()));
        
        producer.start();
        consumer.start();
        producer.join();
        consumer.join();
        
        Integer result = consumed.get();
        assertTrue(result == null || result == 42,
            "Unexpected value: " + result);
    }
    
    @ConcurrentTest(iterations = 1000)
    public void testFifoOrderPreserved() throws InterruptedException {
        LamportBuffer<Integer> buffer = new LamportBuffer<>(8);
        List<Integer> consumed = Collections.synchronizedList(new ArrayList<>());
        
        Thread producer = new Thread(() -> {
            for (int i = 1; i <= 5; i++) {
                while (!buffer.offer(i)) {
                    Thread.yield();
                }
            }
        });
        
        Thread consumer = new Thread(() -> {
            int count = 0;
            while (count < 5) {
                Integer val = buffer.poll();
                if (val != null) {
                    consumed.add(val);
                    count++;
                } else {
                    Thread.yield();
                }
            }
        });
        
        producer.start();
        consumer.start();
        producer.join();
        consumer.join();
        
        assertEquals(List.of(1, 2, 3, 4, 5), consumed);
    }
    
    @ConcurrentTest(iterations = 1000)
    public void testVisibilityWithBrokenBuffer() throws InterruptedException {
        BrokenLamportBuffer<Integer> buffer = new BrokenLamportBuffer<>(4);
        AtomicBoolean sawPartialState = new AtomicBoolean(false);
        
        Thread producer = new Thread(() -> buffer.offer(42));
        
        Thread consumer = new Thread(() -> {
            Integer val = buffer.poll();
            if (val != null && val != 42) {
                sawPartialState.set(true);
            }
        });
        
        producer.start();
        consumer.start();
        producer.join();
        consumer.join();
        
        assertFalse(sawPartialState.get(), "Saw partial/corrupted state!");
    }
}
```

---

## JMH Benchmarks

### Maven Dependencies

```xml
<dependency>
    <groupId>org.openjdk.jmh</groupId>
    <artifactId>jmh-core</artifactId>
    <version>1.37</version>
</dependency>
<dependency>
    <groupId>org.openjdk.jmh</groupId>
    <artifactId>jmh-generator-annprocess</artifactId>
    <version>1.37</version>
    <scope>provided</scope>
</dependency>
```

### Benchmark Classes

```java
import org.openjdk.jmh.annotations.*;

import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.TimeUnit;

@BenchmarkMode(Mode.Throughput)
@OutputTimeUnit(TimeUnit.MILLISECONDS)
@State(Scope.Benchmark)
@Warmup(iterations = 5, time = 1, timeUnit = TimeUnit.SECONDS)
@Measurement(iterations = 5, time = 1, timeUnit = TimeUnit.SECONDS)
@Fork(2)
public class QueueComparisonBenchmark {
    
    private LamportBuffer<Long> lamportBuffer;
    private ArrayBlockingQueue<Long> blockingQueue;
    private SynchronizedQueue<Long> syncQueue;
    
    @Setup
    public void setup() {
        lamportBuffer = new LamportBuffer<>(1024);
        blockingQueue = new ArrayBlockingQueue<>(1024);
        syncQueue = new SynchronizedQueue<>(1024);
    }
    
    // Lamport Buffer benchmarks
    @Benchmark
    @Group("lamport")
    @GroupThreads(1)
    public void lamportProducer() {
        while (!lamportBuffer.offer(1L)) { /* spin */ }
    }
    
    @Benchmark
    @Group("lamport")
    @GroupThreads(1)
    public Long lamportConsumer() {
        Long v;
        while ((v = lamportBuffer.poll()) == null) { /* spin */ }
        return v;
    }
    
    // ArrayBlockingQueue benchmarks
    @Benchmark
    @Group("blocking")
    @GroupThreads(1)
    public void blockingProducer() throws InterruptedException {
        blockingQueue.put(1L);
    }
    
    @Benchmark
    @Group("blocking")
    @GroupThreads(1)
    public Long blockingConsumer() throws InterruptedException {
        return blockingQueue.take();
    }
    
    // Synchronized Queue benchmarks
    @Benchmark
    @Group("synchronized")
    @GroupThreads(1)
    public void syncProducer() {
        while (!syncQueue.offer(1L)) { /* spin */ }
    }
    
    @Benchmark
    @Group("synchronized")
    @GroupThreads(1)
    public Long syncConsumer() {
        Long v;
        while ((v = syncQueue.poll()) == null) { /* spin */ }
        return v;
    }
}

/**
 * Latency benchmark
 */
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.NANOSECONDS)
@State(Scope.Thread)
@Warmup(iterations = 5, time = 1, timeUnit = TimeUnit.SECONDS)
@Measurement(iterations = 5, time = 1, timeUnit = TimeUnit.SECONDS)
@Fork(2)
public class LamportBufferLatencyBenchmark {
    
    private LamportBuffer<Long> buffer;
    
    @Setup
    public void setup() {
        buffer = new LamportBuffer<>(1024);
        for (int i = 0; i < 512; i++) {
            buffer.offer((long) i);
        }
    }
    
    @Benchmark
    public Long offerAndPoll() {
        buffer.offer(42L);
        return buffer.poll();
    }
}
```

### Running Commands

```bash
# Build
mvn clean package

# Run all benchmarks
java -jar target/benchmarks.jar

# Run specific benchmark
java -jar target/benchmarks.jar "QueueComparison.*"

# With GC profiler
java -jar target/benchmarks.jar -prof gc

# Output to JSON
java -jar target/benchmarks.jar -rf json -rff results.json

# Quick run for testing
java -jar target/benchmarks.jar -f 1 -wi 1 -i 1
```

---

## pom.xml Configuration

Complete Maven configuration for a module with all testing tools:

```xml
<?xml version="1.0" encoding="UTF-8"?>
<project xmlns="http://maven.apache.org/POM/4.0.0"
         xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
         xsi:schemaLocation="http://maven.apache.org/POM/4.0.0 
                             http://maven.apache.org/xsd/maven-4.0.0.xsd">
    <modelVersion>4.0.0</modelVersion>
    
    <groupId>com.example</groupId>
    <artifactId>concurrent-testing-demo</artifactId>
    <version>1.0-SNAPSHOT</version>
    
    <properties>
        <maven.compiler.source>21</maven.compiler.source>
        <maven.compiler.target>21</maven.compiler.target>
        <project.build.sourceEncoding>UTF-8</project.build.sourceEncoding>
        <jmh.version>1.37</jmh.version>
        <jcstress.version>0.16</jcstress.version>
    </properties>
    
    <dependencies>
        <!-- JUnit 5 -->
        <dependency>
            <groupId>org.junit.jupiter</groupId>
            <artifactId>junit-jupiter</artifactId>
            <version>5.10.0</version>
            <scope>test</scope>
        </dependency>
        
        <!-- JMH -->
        <dependency>
            <groupId>org.openjdk.jmh</groupId>
            <artifactId>jmh-core</artifactId>
            <version>${jmh.version}</version>
        </dependency>
        <dependency>
            <groupId>org.openjdk.jmh</groupId>
            <artifactId>jmh-generator-annprocess</artifactId>
            <version>${jmh.version}</version>
            <scope>provided</scope>
        </dependency>
        
        <!-- jcstress -->
        <dependency>
            <groupId>org.openjdk.jcstress</groupId>
            <artifactId>jcstress-core</artifactId>
            <version>${jcstress.version}</version>
        </dependency>
        
        <!-- Fray -->
        <dependency>
            <groupId>org.pastalab</groupId>
            <artifactId>fray-junit</artifactId>
            <version>0.2.2</version>
            <scope>test</scope>
        </dependency>
    </dependencies>
    
    <build>
        <plugins>
            <plugin>
                <groupId>org.apache.maven.plugins</groupId>
                <artifactId>maven-shade-plugin</artifactId>
                <version>3.5.1</version>
                <executions>
                    <execution>
                        <phase>package</phase>
                        <goals>
                            <goal>shade</goal>
                        </goals>
                        <configuration>
                            <transformers>
                                <transformer implementation=
                                    "org.apache.maven.plugins.shade.resource.ManifestResourceTransformer">
                                    <mainClass>org.openjdk.jmh.Main</mainClass>
                                </transformer>
                            </transformers>
                            <finalName>benchmarks</finalName>
                        </configuration>
                    </execution>
                </executions>
            </plugin>
        </plugins>
    </build>
</project>
```

