package pl.wsztajerowski.entities.jcstress;

import dev.morphia.annotations.Entity;
import dev.morphia.annotations.Id;

@Entity("jcstress_tests")
public record JCStressTest(
    @Id
    String requestId,
    JCStressTestMetadata metadata,
    JCStressResult result) {
}
