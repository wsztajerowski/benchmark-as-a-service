# Part 3: Fray-based Tests
## Duration: ~10 minutes

---

## What is Fray?

**Fray** is a systematic concurrency testing tool from Microsoft Research that uses **controlled concurrency testing (CCT)** to explore thread interleavings.

```mermaid
flowchart LR
    subgraph Fray["Fray Approach"]
        S["Scheduler<br/>Controller"] --> T1["Thread 1"]
        S --> T2["Thread 2"]
        S --> T3["Thread N"]
        
        T1 --> O["Observer"]
        T2 --> O
        T3 --> O
        
        O --> S
    end
    
    S -->|"Systematic<br/>Exploration"| Result["Coverage<br/>Report"]
    
    style S fill:#DDA0DD
```

**Key Difference:** Fray **controls** thread scheduling, systematically exploring different execution orders.

---

## jcstress vs Fray: Approaches Compared

```mermaid
flowchart TB
    subgraph JCS["jcstress"]
        direction TB
        J1["🎲 Random Scheduling"]
        J2["🔄 Millions of Iterations"]
        J3["📊 Statistical Analysis"]
        J4["🖥️ Real Hardware Concurrency"]
    end
    
    subgraph Fray["Fray"]
        direction TB
        F1["🎯 Controlled Scheduling"]
        F2["🔍 Systematic Exploration"]
        F3["✅ Coverage Guarantees"]
        F4["🔁 Deterministic Replay"]
    end
    
    JCS -.->|"Probabilistic"| Result1["May miss rare bugs"]
    Fray -.->|"Systematic"| Result2["Guaranteed exploration"]
```

---

## Comparison Table

| Aspect | jcstress | Fray |
|--------|----------|------|
| **Approach** | Probabilistic stress | Systematic exploration |
| **Scheduling** | Real OS scheduler | Controlled scheduler |
| **Coverage** | Statistical confidence | Bounded exhaustive |
| **Speed** | Very fast | Slower (explores paths) |
| **Reproducibility** | Hard to reproduce | Deterministic replay |
| **Bug Discovery** | "Probably works" | "Proven for N interleavings" |
| **Memory Model** | Tests real JVM/CPU | Simulated (sequentially consistent) |

---

## How Fray Works

```mermaid
sequenceDiagram
    participant S as Fray Scheduler
    participant T1 as Thread 1
    participant T2 as Thread 2
    
    Note over S: Iteration 1 - Schedule A
    S->>T1: Run until sync point
    T1->>T1: buffer.offer(1)
    T1->>S: Sync point reached
    S->>T2: Run until sync point
    T2->>T2: buffer.poll()
    T2->>S: Sync point reached
    Note over S: Record outcome
    
    Note over S: Iteration 2 - Schedule B
    S->>T2: Run first this time
    T2->>T2: buffer.poll() → null
    T2->>S: Sync point reached
    S->>T1: Now run producer
    T1->>T1: buffer.offer(1)
    T1->>S: Sync point reached
    Note over S: Different outcome - valid
```

---

## Setting Up Fray

### Maven Dependency

```xml
<dependency>
    <groupId>org.pastalab</groupId>
    <artifactId>fray-junit</artifactId>
    <version>0.2.2</version>
    <scope>test</scope>
</dependency>
```

### JVM Agent Configuration

```bash
# Fray requires a Java agent for bytecode instrumentation
java -javaagent:fray-agent.jar \
     -Dfray.scheduler=random \
     -Dfray.iterations=1000 \
     -jar your-tests.jar
```

---

## Fray Test for Lamport Buffer

```java
import org.pastalab.fray.junit.FrayTest;
import org.pastalab.fray.junit.annotations.ConcurrentTest;
import static org.junit.jupiter.api.Assertions.*;

public class LamportBufferFrayTest {
    
    @ConcurrentTest(iterations = 1000)
    public void testProducerConsumer() throws InterruptedException {
        LamportBuffer<Integer> buffer = new LamportBuffer<>(4);
        AtomicReference<Integer> consumed = new AtomicReference<>();
        
        Thread producer = new Thread(() -> {
            buffer.offer(42);
        });
        
        Thread consumer = new Thread(() -> {
            Integer result = buffer.poll();
            consumed.set(result);
        });
        
        producer.start();
        consumer.start();
        
        producer.join();
        consumer.join();
        
        // Either consumer saw the value, or it was empty (ran first)
        Integer result = consumed.get();
        assertTrue(result == null || result == 42,
            "Unexpected value: " + result);
    }
}
```

---

## Testing FIFO Order with Fray

```java
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
    
    // Verify FIFO order
    assertEquals(List.of(1, 2, 3, 4, 5), consumed,
        "Elements must be consumed in FIFO order");
}
```

---

## Detecting Visibility Bugs with Fray

```java
@ConcurrentTest(iterations = 1000)
public void testVisibilityWithBrokenBuffer() throws InterruptedException {
    BrokenLamportBuffer<Integer> buffer = new BrokenLamportBuffer<>(4);
    AtomicInteger result = new AtomicInteger(-999);
    AtomicBoolean sawPartialState = new AtomicBoolean(false);
    
    Thread producer = new Thread(() -> {
        buffer.offer(42);
    });
    
    Thread consumer = new Thread(() -> {
        // Might see partial state without volatile
        Integer val = buffer.poll();
        if (val != null && val != 42) {
            sawPartialState.set(true);
        }
        result.set(val == null ? -1 : val);
    });
    
    producer.start();
    consumer.start();
    producer.join();
    consumer.join();
    
    assertFalse(sawPartialState.get(), 
        "Saw partial/corrupted state - visibility bug!");
}
```

---

## Fray Scheduler Strategies

```mermaid
flowchart TB
    subgraph Strategies["Scheduling Strategies"]
        R["Random"]
        PCT["PCT<br/>(Probabilistic<br/>Concurrency Testing)"]
        POS["POS<br/>(Partial Order<br/>Sampling)"]
        DFS["DFS<br/>(Depth-First<br/>Search)"]
    end
    
    R -->|"Quick exploration"| Fast["Fast, good coverage"]
    PCT -->|"Bug-finding heuristic"| Bugs["Finds deep bugs"]
    POS -->|"Prioritizes unique paths"| Cover["Maximum coverage"]
    DFS -->|"Exhaustive"| Complete["Complete (if feasible)"]
```

---

## Running Fray Tests

```bash
# Using Maven
mvn test -Dfray.enabled=true

# With specific scheduler
mvn test -Dfray.scheduler=pct -Dfray.iterations=5000

# Generate coverage report
mvn test -Dfray.report=true
```

### Sample Output

```
Fray Concurrency Test Report
============================

testProducerConsumer:
  Iterations: 1000
  Unique schedules explored: 847
  Outcomes:
    - Consumer saw 42: 523 (52.3%)
    - Consumer saw null: 477 (47.7%)
  Status: PASSED

testFifoOrderPreserved:
  Iterations: 1000
  Unique schedules explored: 2341
  Status: PASSED
  
testVisibilityWithBrokenBuffer:
  Iterations: 1000
  Status: FAILED at iteration 127
  Bug: Assertion failed - saw partial state
  Replay command: mvn test -Dfray.replay=127
```

---

## The Killer Feature: Deterministic Replay

```mermaid
flowchart LR
    Bug["Bug Found<br/>Iteration 127"] --> Record["Schedule<br/>Recorded"]
    Record --> Replay["Deterministic<br/>Replay"]
    Replay --> Debug["Debug with<br/>Breakpoints"]
    Debug --> Fix["Fix the Bug"]
    Fix --> Verify["Replay Passes"]
```

```bash
# Replay the exact failing schedule
mvn test -Dfray.replay=127

# Attach debugger to the replayed execution
mvn test -Dfray.replay=127 -Dfray.debug=true
```

**Why This Matters:** Concurrency bugs are notoriously hard to reproduce. Fray captures the exact thread schedule and replays it!

---

## Fray Limitations

| Limitation | Description |
|------------|-------------|
| 🐢 **Slower** | Systematic exploration takes time |
| 📐 **State Space** | Exponential interleavings for complex code |
| 🔧 **Instrumentation** | Requires Java agent setup |
| 💻 **Simulated Concurrency** | Doesn't test real CPU reordering |
| 🧪 **Newer Tool** | Less mature ecosystem than jcstress |

---

## When to Use Each Tool

```mermaid
flowchart TB
    Q1{"Testing concurrent code?"}
    Q1 -->|No| Skip["Unit tests sufficient"]
    Q1 -->|Yes| Q2{"Need quick feedback?"}
    
    Q2 -->|Yes| JCS["Use jcstress<br/>Fast, probabilistic"]
    Q2 -->|No| Q3{"Need guaranteed coverage?"}
    
    Q3 -->|Yes| Fray["Use Fray<br/>Systematic exploration"]
    Q3 -->|No| Q4{"Debugging specific bug?"}
    
    Q4 -->|Yes| Fray2["Use Fray<br/>Deterministic replay"]
    Q4 -->|No| Both["Use both<br/>jcstress + Fray"]
    
    style JCS fill:#87CEEB
    style Fray fill:#DDA0DD
    style Fray2 fill:#DDA0DD
    style Both fill:#90EE90
```

---

## Complementary Testing Strategy

```mermaid
flowchart LR
    subgraph CI["CI Pipeline"]
        U["Unit Tests<br/>Every commit"]
        J["jcstress<br/>Nightly"]
        F["Fray<br/>Weekly/Release"]
    end
    
    U -->|"Pass"| J
    J -->|"Pass"| F
    F -->|"Pass"| Release["Release<br/>Candidate"]
    
    style U fill:#90EE90
    style J fill:#87CEEB
    style F fill:#DDA0DD
```

| Tool | When to Run | Time Budget |
|------|-------------|-------------|
| JUnit | Every commit | Seconds |
| jcstress | Nightly builds | Minutes |
| Fray | Pre-release | Minutes to hours |

---

## Key Takeaways - Part 3

1. ✅ **Fray systematically explores** thread interleavings
2. ✅ **Deterministic replay** enables debugging
3. ✅ **Guaranteed coverage** within bounds
4. ⚠️ **Slower** than jcstress due to systematic nature
5. ⚠️ **Different focus** - Fray for correctness, jcstress for real hardware
6. 📌 **Best together** - use both for comprehensive coverage

---

## Transition to Part 4

> "We've verified our Lamport buffer works correctly under all thread schedules. But does it actually perform well? How does it compare to a synchronized queue? What's the throughput and latency?"

**Next:** Enter **JMH** - Java Microbenchmark Harness for performance testing.

