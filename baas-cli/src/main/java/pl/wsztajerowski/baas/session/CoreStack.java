package pl.wsztajerowski.baas.session;

/**
 * The core stack's interface as the CLI reads and writes it: the outputs it reads back and the
 * parameters it sets by name. The template declares them; {@code CoreStackTemplateTest} pins each
 * name here to a declaration there, so a renamed output fails a unit test instead of a paid job
 * that cannot find its subnet. The runner-image parameters have their own contract,
 * {@code RunnerImageParameters}.
 */
public final class CoreStack {

    /** The runner subnet, read per job. */
    public static final String OUTPUT_SUBNET_ID = "SubnetId";
    /** The runner security group, read per job — never cached, since the group's id can move. */
    public static final String OUTPUT_SECURITY_GROUP_ID = "SecurityGroupId";
    /** The role every day-to-day command runs as. */
    public static final String OUTPUT_OPERATOR_ROLE_ARN = "OperatorRoleArn";
    /** The Image Builder pipeline {@code baas admin image build} starts. */
    public static final String OUTPUT_RUNNER_IMAGE_PIPELINE_ARN = "RunnerImagePipelineArn";

    /** The whole name stem: the stack, the bucket and every derived resource name. */
    public static final String PARAM_RESOURCE_NAME_PREFIX = "ResourceNamePrefix";
    /** GitHub organisation whose workflows may assume the operator role; empty disables federation. */
    public static final String PARAM_GITHUB_ORG = "GitHubOrg";
    /** Already-composed {@code repo:<org>/<name>:*} subject patterns. */
    public static final String PARAM_GITHUB_REPO = "GitHubRepo";
    /**
     * The release of the CLI that last ran setup on the stack; empty when none is recorded. Only
     * setup submits it, and every command refuses a CLI older than it.
     */
    public static final String PARAM_BAAS_CLI_VERSION = "BaasCliVersion";

    private CoreStack() {
    }
}
