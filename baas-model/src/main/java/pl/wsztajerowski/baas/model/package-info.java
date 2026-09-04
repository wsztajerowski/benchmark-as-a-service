/**
 * Shared measurement model for the DynamoDB results store.
 *
 * <p>Holds the stored measurement shape, key encoding and tag vocabulary used by both
 * {@code benchmark-runner} (which writes results) and {@code baas-cli} (which reads them). This
 * module sits on {@code baas-cli}'s classpath, so it must never depend on MongoDB or Morphia —
 * enforced mechanically by the {@code ban-mongodb} rule in this module's {@code pom.xml}, not by
 * convention.
 */
package pl.wsztajerowski.baas.model;
