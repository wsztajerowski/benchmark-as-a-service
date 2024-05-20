package pl.symentis.infra;

import dev.morphia.annotations.Entity;
import dev.morphia.annotations.Id;

@Entity("test-entities")
public record TestEntity(@Id String name, int quantity) {
}
