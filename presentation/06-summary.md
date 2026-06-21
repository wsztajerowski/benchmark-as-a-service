# Summary: Testing Pyramid for Concurrency
## Duration: ~2 minutes

---

## The Journey Recap

```mermaid
timeline
    title Our Testing Journey with Lamport Buffer
    
    section Part 1
        Unit Tests : Verify sequential logic
        : Basic FIFO, capacity, wrap-around
        : ✅ Fast, ❌ Misses concurrency bugs
    
    section Part 2
        jcstress : Probabilistic stress testing
        : Millions of iterations
        : ✅ Finds real bugs, ❌ May miss rare cases
    
    section Part 3
        Fray : Systematic exploration
        : Controlled scheduling
        : ✅ Guaranteed coverage, ❌ Slower
    
    section Part 4
        JMH : Performance measurement
        : Throughput, latency benchmarks
        : ✅ Accurate metrics, ❌ Only for correct code
```

---

## The Testing Pyramid for Concurrent Code

```mermaid
graph TB
    subgraph Pyramid["Testing Pyramid"]
        JMH["⚡ JMH<br/>Performance"]
        Fray["🔍 Fray<br/>Systematic"]
        JCS["🔀 jcstress<br/>Probabilistic"]
        Unit["✅ JUnit<br/>Sequential"]
    end
    
    Unit --> JCS
    JCS --> Fray
    Fray --> JMH
    
    subgraph Labels["Focus"]
        L1["Fast Feedback"]
        L2["Stress Testing"]
        L3["Coverage Guarantee"]
        L4["Optimization"]
    end
    
    Unit -.-> L1
    JCS -.-> L2
    Fray -.-> L3
    JMH -.-> L4
    
    style Unit fill:#90EE90
    style JCS fill:#87CEEB
    style Fray fill:#DDA0DD
    style JMH fill:#FFB347
```

---

## Tool Selection Guide

| Question | Tool |
|----------|------|
| Does the logic work at all? | **JUnit** |
| Does it work under thread contention? | **jcstress** |
| Does it work for ALL possible schedules? | **Fray** |
| How fast is it? | **JMH** |
| Can I reproduce a concurrency bug? | **Fray** |
| What happens on real hardware? | **jcstress** |

---

## Decision Flowchart

```mermaid
flowchart TD
    Start["I have concurrent code"] --> Q1{"Basic logic<br/>tested?"}
    
    Q1 -->|"No"| JUnit["Write JUnit tests"]
    JUnit --> Q1
    
    Q1 -->|"Yes"| Q2{"Need quick<br/>feedback?"}
    
    Q2 -->|"Yes"| JCS["Use jcstress"]
    Q2 -->|"No"| Q3{"Need guaranteed<br/>coverage?"}
    
    Q3 -->|"Yes"| Fray["Use Fray"]
    Q3 -->|"No"| Q4{"Debugging<br/>a bug?"}
    
    Q4 -->|"Yes"| Fray2["Use Fray<br/>(replay)"]
    Q4 -->|"No"| Both["Use jcstress + Fray"]
    
    JCS --> Q5{"Need to measure<br/>performance?"}
    Fray --> Q5
    Fray2 --> Q5
    Both --> Q5
    
    Q5 -->|"Yes"| JMH["Use JMH"]
    Q5 -->|"No"| Done["Done!"]
    JMH --> Done
    
    style JUnit fill:#90EE90
    style JCS fill:#87CEEB
    style Fray fill:#DDA0DD
    style Fray2 fill:#DDA0DD
    style JMH fill:#FFB347
```

---

## CI/CD Integration Strategy

```mermaid
flowchart LR
    subgraph Commit["Every Commit"]
        U["JUnit Tests<br/>~seconds"]
    end
    
    subgraph Nightly["Nightly Build"]
        S["jcstress<br/>~minutes"]
    end
    
    subgraph Release["Pre-Release"]
        F["Fray<br/>~10+ minutes"]
        B["JMH Benchmarks<br/>~10+ minutes"]
    end
    
    U --> S --> F --> B --> Deploy["Deploy"]
    
    style U fill:#90EE90
    style S fill:#87CEEB
    style F fill:#DDA0DD
    style B fill:#FFB347
```

---

## Resource Links

### Tools

| Tool | Link | Documentation |
|------|------|---------------|
| **jcstress** | [github.com/openjdk/jcstress](https://github.com/openjdk/jcstress) | Samples in repo |
| **Fray** | [github.com/cmu-pasta/fray](https://github.com/cmu-pasta/fray) | README + Wiki |
| **JMH** | [github.com/openjdk/jmh](https://github.com/openjdk/jmh) | [JMH Samples](http://hg.openjdk.java.net/code-tools/jmh/file/tip/jmh-samples/src/main/java/org/openjdk/jmh/samples/) |

### Further Reading

- [Java Memory Model (JLS Chapter 17)](https://docs.oracle.com/javase/specs/jls/se17/html/jls-17.html)
- [Aleksey Shipilёv's Blog](https://shipilev.net/) - JMH author, performance expert
- [Lamport's Original Paper](https://lamport.azurewebsites.net/pubs/pubs.html)

---

## Key Lessons

```mermaid
mindmap
  root((Testing Concurrent Code))
    Unit Tests
      Necessary foundation
      Not sufficient alone
      Fast feedback
    jcstress
      Probabilistic stress
      Real hardware
      Quick to run
    Fray
      Systematic exploration
      Deterministic replay
      Guaranteed coverage
    JMH
      Accurate benchmarks
      JVM-aware
      Statistical analysis
    Strategy
      Layer all four
      Run at different cadences
      Only optimize correct code
```

---

## Final Thoughts

> **"Testing concurrent code is hard. Not testing concurrent code is catastrophic."**

### The Three Rules

1. **Never trust single-threaded tests** for concurrent code
2. **Combine probabilistic AND systematic** testing
3. **Only benchmark code that is proven correct**

---

## Q&A

```mermaid
flowchart LR
    Q["❓ Questions?"] --> A["🎤 Discussion"]
    A --> T["☕ Thank You!"]
    
    style Q fill:#FFB347
    style A fill:#87CEEB
    style T fill:#90EE90
```

---

## Contact & Resources

- **Presentation Materials:** [Available in this repository]
- **Code Examples:** [benchmark-as-a-service/presentation/code/]
- **Follow-up Questions:** [Your contact info here]

### Thank You!

```
🎉 Happy Testing! 🎉
```

