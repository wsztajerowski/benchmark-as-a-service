package pl.wsztajerowski.entities.jcstress;

import dev.morphia.annotations.Entity;

import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Collections;
import java.util.Map;

@Entity
public record JCStressTestMetadata(Map<String, String> tags, LocalDateTime createdAt) {
    public JCStressTestMetadata() {
        this(Collections.emptyMap(),OffsetDateTime.now(ZoneOffset.UTC).toLocalDateTime());
    }
    public JCStressTestMetadata(Map<String, String> tags) {
        this(tags,OffsetDateTime.now(ZoneOffset.UTC).toLocalDateTime());
    }
}
