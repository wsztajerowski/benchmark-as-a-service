package pl.wsztajerowski.baas.infra;

import software.amazon.awssdk.services.ssm.SsmClient;
import software.amazon.awssdk.services.ssm.model.GetParameterRequest;
import software.amazon.awssdk.services.ssm.model.GetParameterResponse;
import software.amazon.awssdk.services.ssm.model.Parameter;
import software.amazon.awssdk.services.ssm.model.ParameterNotFoundException;
import software.amazon.awssdk.services.ssm.model.PutParameterRequest;
import software.amazon.awssdk.services.ssm.model.PutParameterResponse;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** In-memory SSM parameter store, sharing {@link FakeEc2}'s call log so orderings are assertable. */
class FakeSsm implements SsmClient {

    final Map<String, String> parameters = new LinkedHashMap<>();
    final List<String> calls;

    FakeSsm(List<String> calls) {
        this.calls = calls;
    }

    FakeSsm() {
        this(new ArrayList<>());
    }

    @Override
    public GetParameterResponse getParameter(GetParameterRequest request) {
        String value = parameters.get(request.name());
        if (value == null) {
            throw ParameterNotFoundException.builder().message(request.name() + " not found").build();
        }
        return GetParameterResponse.builder()
            .parameter(Parameter.builder().name(request.name()).value(value).build())
            .build();
    }

    @Override
    public PutParameterResponse putParameter(PutParameterRequest request) {
        calls.add("putParameter:" + request.value());
        parameters.put(request.name(), request.value());
        return PutParameterResponse.builder().build();
    }

    @Override
    public String serviceName() {
        return "ssm";
    }

    @Override
    public void close() {
    }
}
