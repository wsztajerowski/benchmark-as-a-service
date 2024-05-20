package pl.symentis.entities.jcstress;

import dev.morphia.annotations.Entity;

import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;

@Entity
public record JCStressTestMetadata(LocalDateTime createdAt) {
    public JCStressTestMetadata() {
        this(OffsetDateTime.now(ZoneOffset.UTC).toLocalDateTime());
    }
}
