package pl.symentis.entities.jcstress;

import dev.morphia.annotations.Entity;
import dev.morphia.annotations.Id;

@Entity("jcstress-tests")
public record JCStressTest(
    @Id
    String requestId,
    JCStressTestMetadata metadata,
    JCStressResult result) {
}
