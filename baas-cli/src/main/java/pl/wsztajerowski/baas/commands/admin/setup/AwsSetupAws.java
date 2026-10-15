package pl.wsztajerowski.baas.commands.admin.setup;

import pl.wsztajerowski.baas.infra.DeployerSession;

import pl.wsztajerowski.baas.config.CoreStack;

import pl.wsztajerowski.baas.commands.admin.RunnerImageBuild;
import pl.wsztajerowski.baas.config.DeploymentNames;
import pl.wsztajerowski.baas.infra.AwsClientFactory;
import pl.wsztajerowski.baas.infra.BucketProbe;
import pl.wsztajerowski.baas.infra.CloudFormationService;
import pl.wsztajerowski.baas.infra.DeployerPreflight;
import pl.wsztajerowski.baas.infra.ImageBuilderService;
import pl.wsztajerowski.baas.infra.OperatorSession;
import pl.wsztajerowski.baas.infra.ParentImageResolver;
import pl.wsztajerowski.baas.infra.RunnerImageParameters;
import pl.wsztajerowski.baas.infra.RunnerImageRenderer;
import software.amazon.awssdk.services.cloudformation.model.StackStatus;
import software.amazon.awssdk.services.dynamodb.model.ResourceNotFoundException;
import software.amazon.awssdk.services.iam.model.NoSuchEntityException;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * {@link SetupAws} over the services setup already used, for one region and one deployer profile.
 * Mapping only: the logic stays in the services, where it is tested on its own.
 */
public final class AwsSetupAws implements SetupAws {

    private static final Duration STACK_POLL = Duration.ofSeconds(10);

    private final String region;
    private final String profile;
    private final AwsClientFactory factory;

    public AwsSetupAws(String region, String profile) {
        this.region = region;
        this.profile = profile;
        this.factory = DeployerSession.factory(region, profile);
    }

    @Override
    public Caller callerIdentity() {
        try (var sts = factory.sts()) {
            var identity = sts.getCallerIdentity();
            return new Caller(identity.arn(), identity.account());
        }
    }

    @Override
    public BucketProbe bucket(String name) {
        return BucketProbe.of(name, region, factory::s3In);
    }

    @Override
    public boolean tableExists(String name) {
        try (var dynamoDb = factory.dynamoDb()) {
            dynamoDb.describeTable(r -> r.tableName(name));
            return true;
        } catch (ResourceNotFoundException e) {
            return false;
        }
    }

    @Override
    public Preflight preflight(String callerArn, String policy) {
        try (var iam = factory.iam()) {
            return new DeployerPreflight(iam).check(callerArn, policy)
                .<Preflight>map(denied -> denied.isEmpty() ? new Preflight.Met() : new Preflight.Denied(denied))
                .orElseGet(Preflight.NotChecked::new);
        }
    }

    @Override
    public Optional<StackStatus> stackStatus(String stack) {
        try (var cf = factory.cloudFormation()) {
            return new CloudFormationService(cf).stackStatus(stack);
        }
    }

    @Override
    public Optional<StackStatus> awaitSettled(String stack) throws InterruptedException {
        try (var cf = factory.cloudFormation()) {
            return new CloudFormationService(cf).awaitSettled(stack, STACK_POLL);
        }
    }

    @Override
    public void deleteStack(String stack) {
        try (var cf = factory.cloudFormation()) {
            new CloudFormationService(cf).deleteStack(stack);
        }
    }

    @Override
    public Map<String, String> stackParameters(String stack) {
        try (var cf = factory.cloudFormation()) {
            return new CloudFormationService(cf).getStackParameters(stack);
        }
    }

    @Override
    public Map<String, String> stackOutputs(String stack) {
        try (var cf = factory.cloudFormation()) {
            return new CloudFormationService(cf).getStackOutputs(stack);
        }
    }

    @Override
    public Map<String, String> imageParameters(Map<String, String> deployed) {
        var renderer = new RunnerImageRenderer();
        String parentAmiId = deployed.get(RunnerImageParameters.PARENT_AMI_ID);
        if (parentAmiId == null) {
            try (var ec2 = factory.ec2()) {
                parentAmiId = new ParentImageResolver(ec2).resolve(renderer.definition().parentImage().amiName(), region);
            }
        }
        var plan = RunnerImageParameters.plan(renderer,
            deployed.getOrDefault(RunnerImageParameters.IMAGE_VERSION, renderer.definition().imageVersion()),
            deployed.getOrDefault(RunnerImageParameters.COMPONENT_DATA, renderer.renderBase()),
            parentAmiId,
            deployed.getOrDefault(RunnerImageParameters.EXTENSION_DATA, ""),
            deployed);
        return deployed.isEmpty() ? plan.values() : plan.absentFrom(deployed);
    }

    @Override
    public void submitCreate(String stack, Map<String, String> parameters) {
        try (var cf = factory.cloudFormation()) {
            new CloudFormationService(cf).submitCreate(stack, template(), parameters);
        }
    }

    @Override
    public void awaitCreate(String stack) {
        try (var cf = factory.cloudFormation()) {
            new CloudFormationService(cf).awaitCreate(stack);
        }
    }

    @Override
    public boolean update(String stack, Map<String, String> parameters) {
        try (var cf = factory.cloudFormation()) {
            return new CloudFormationService(cf).updateStackParameters(stack, template(), parameters);
        }
    }

    @Override
    public void assumeRole(String roleArn) {
        OperatorSession.REAL.assume(AwsClientFactory.profile(profile), region, roleArn);
    }

    @Override
    public OidcProvider oidcProvider(String arn) {
        try (var iam = factory.iam()) {
            return new OidcProvider.Present(
                iam.getOpenIDConnectProvider(r -> r.openIDConnectProviderArn(arn)).clientIDList());
        } catch (NoSuchEntityException e) {
            return new OidcProvider.Absent();
        }
    }

    @Override
    public void createOidcProvider() {
        try (var iam = factory.iam()) {
            iam.createOpenIDConnectProvider(r -> r
                .url("https://" + GITHUB_ISSUER)
                .clientIDList(List.of(STS_AUDIENCE)));
        }
    }

    @Override
    public Image image(String deployment) {
        try (var imageBuilder = factory.imageBuilder(); var ec2 = factory.ec2(); var ssm = factory.ssm()) {
            var current = new ImageBuilderService(imageBuilder, ec2, ssm)
                .currentImage(DeploymentNames.of(deployment).amiPointer());
            if (current.isEmpty() || current.get().imageVersion() == null) {
                return new Image.Absent();
            }
            String label = current.get().imageVersion();
            String base = label.split("\\+", 2)[0];
            String bundled = new RunnerImageRenderer().definition().imageVersion();
            return RunnerImageParameters.compareVersions(bundled, base) > 0
                ? new Image.Behind(label)
                : new Image.Current(label);
        }
    }

    @Override
    public Optional<RunningBuild> runningImageBuild(String deployment) {
        String pipeline = stackOutputs(deployment).get(CoreStack.OUTPUT_RUNNER_IMAGE_PIPELINE_ARN);
        if (pipeline == null || pipeline.isEmpty()) {
            return Optional.empty();
        }
        try (var imageBuilder = factory.imageBuilder(); var ec2 = factory.ec2(); var ssm = factory.ssm()) {
            return new ImageBuilderService(imageBuilder, ec2, ssm).runningBuild(pipeline)
                .map(build -> new RunningBuild(build.started()));
        }
    }

    @Override
    public String buildImage(String deployment) throws InterruptedException {
        try {
            return RunnerImageBuild.run(factory, deployment, region, Optional.empty()).amiId();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static String template() {
        try {
            return CloudFormationService.coreTemplate();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
