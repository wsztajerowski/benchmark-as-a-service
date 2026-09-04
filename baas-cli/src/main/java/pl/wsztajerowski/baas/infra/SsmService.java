package pl.wsztajerowski.baas.infra;

import software.amazon.awssdk.services.ssm.SsmClient;
import software.amazon.awssdk.services.ssm.model.DeleteParameterRequest;
import software.amazon.awssdk.services.ssm.model.GetParameterRequest;
import software.amazon.awssdk.services.ssm.model.ParameterNotFoundException;
import software.amazon.awssdk.services.ssm.model.ParameterType;
import software.amazon.awssdk.services.ssm.model.PutParameterRequest;

import java.util.Optional;

public class SsmService {

    private final SsmClient ssm;

    public SsmService(SsmClient ssm) {
        this.ssm = ssm;
    }

    public String getParameter(String name) {
        return ssm.getParameter(GetParameterRequest.builder()
            .name(name)
            .withDecryption(true)
            .build())
            .parameter().value();
    }

    public Optional<String> getParameterOptional(String name) {
        try {
            return Optional.of(getParameter(name));
        } catch (ParameterNotFoundException e) {
            return Optional.empty();
        }
    }

    /**
     * A plain String parameter, for values that are not secrets — the runner AMI pointer is read by
     * every {@code baas run}, and a SecureString would need {@code kms:Decrypt} on the operator role
     * to publish an ID that is not sensitive.
     */
    public void putStringParameter(String name, String value) {
        ssm.putParameter(PutParameterRequest.builder()
            .name(name)
            .value(value)
            .type(ParameterType.STRING)
            .overwrite(true)
            .build());
    }

    public void putSecureParameter(String name, String value) {
        ssm.putParameter(PutParameterRequest.builder()
            .name(name)
            .value(value)
            .type(ParameterType.SECURE_STRING)
            .overwrite(true)
            .build());
    }

    public void deleteParameter(String name) {
        try {
            ssm.deleteParameter(DeleteParameterRequest.builder().name(name).build());
        } catch (ParameterNotFoundException ignored) {
        }
    }
}
