# Option lists and parser behaviour (tasks 1.1–1.3)

Probed 2026-10-31 on the laptop (Java 25) against the reactor's fixture JARs: JMH 1.37
(`fake-jmh-benchmarks.jar`) and the JCStress in `fake-stress-tests.jar`.

## JMH 1.37 (`-h`)

All value-taking options take a **required** value.

| Kind | Options |
|---|---|
| Value | `i` `bs` `r` `wi` `wbs` `w` `to` `t` `bm` `si` `gc` `foe` `v` `f` `wf` `o` `rff` `prof` `tg` `jvm` `jvmArgs` `jvmArgsAppend` `jvmArgsPrepend` `tu` `opi` `rf` `wm` `e` `p` `wmb` |
| Flag | `l` `lp` `lrf` `lprof` `h` |

## JCStress (`-h`)

`[x]` in the help means the value is **optional**.

| Kind | Options |
|---|---|
| Required value | `c` `hs` `iters` `jvmArgs` `jvmArgsPrepend` `m` `p` `r` `spinStyle` `strideCount` `strideSize` `t` `time` |
| Optional value | `af` `f` `fsm` `l` `pth` `sc` |
| Flag | `h` `v` `vv` `vvv` |

## How jopt-simple reads a token (observed)

1. **`--name[=value]`**:
   - an exact name, or a prefix that begins exactly one name (`--prof gc`);
   - no clustering.
2. **`-body`**: take `key` = `body` up to the first `=`.
   - If `key` is an exact name, or a unique prefix, that option is meant, with the value after `=`:
     - `-pro gc` and `-pro=gc` are `-prof`;
     - `-lr` is `-lrf`;
     - `-lpr` is `-lprof`;
     - `-wb 1` is `-wbs`;
     - `-jvmArgsA` is `-jvmArgsAppend`.
   - Otherwise (no match, or **an ambiguous prefix**), the body is a **cluster of single-letter
     options**, read left to right:
     - a letter whose option takes a value takes the rest of the token as that value (`-oout2.txt` is
       `-o out2.txt` and **wrote `out2.txt`**; `-pgc=1` is `-p gc=1`; `-ti` is `-t i`; JCStress `-rx`
       is `-r x`);
     - a flag letter is read and the next letter follows it (`-hl` and `-lh` both print help);
     - an unknown letter is a parse error (`-jvmA x` gives "j is not a recognized option", because
       `jvmA` is ambiguous among the three `jvmArgs*`; `-Xfoo` gives "X is not a recognized option").
3. **The value of a required-value option** given as the next token is consumed whatever it looks
   like: `-jvmArgs -rf -l` lists benchmarks, so `-rf` there was `-jvmArgs`' value.
4. **An exact name always wins over a longer name it prefixes**: `-r`, `-rf`, `-l`, `-lp` and `-p`.

## `-prof async` with appended keys

```
-prof "async:output=flamegraph;event=cpu;dir=<abs>/profiler-output;libPath=$ASYNC_PATH"
```

This wrote `profiler-output/pl.wsztajerowski.fake.Incrementing_Synchronized.incrementUsingSynchronized-Throughput/flame-cpu-{forward,reverse}.html`.
Appending `dir` and `libPath` after the user's own keys works, and the artifacts land in one directory
per benchmark under `dir`, as `ProfilerArtifacts` expects.

## Profiler option strings

JMH 1.37 (`ProfilerUtils.parseInitLine`) splits a `-prof` value's options on `;`, puts `-` in front of
each piece, and parses them with jopt-simple. Profiler keys therefore abbreviate too: `jfr:di=x` wrote
to `x`, and `jfr:-dir=x` was accepted.

A key given twice is refused: `jfr:dir=a;dir=b` gives "Found multiple arguments for option dir",
"Profilers failed to initialize, exiting.", and **exit 1**. An ambiguous key fails the same way: async
`di=…`, because `di` begins both `dir` and `direction`.

Because the runner always appends `dir=` (and `libPath=` for async), a user's own value for either
key, in any spelling, collides with the injected one, and the job fails on the instance with JMH's
error. The refusal before launch therefore matches the exact key, with or without leading dashes, to
give a clear message. An abbreviated key still fails safe.
