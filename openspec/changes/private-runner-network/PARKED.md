# Parked — do not apply

Parked 2026-10-02 at the owner's request: not to be worked on now, and not the next item. See
[`../QUEUE.md`](../QUEUE.md). Unpark by deleting this file and adding the change to that queue.

Before `/opsx:apply`, run `/opsx:update private-runner-network`. The artifacts predate changes that
have since landed:

- **§4 Runner JAR staging is already done.** `unified-run-prefix` seeds
  `releases/<version>/benchmark-runner.jar` once, and the CLI verifies it against a published
  `.sha256`. Tasks 4.1–4.6, and the proposal's "GitHub Releases is unreachable" argument, describe
  work that has already happened.
- **`design.md:13` argues against an accepted risk that no longer exists** (a private subnet costing
  about $32/month). That row is no longer in CLAUDE.md's *Accepted risks*.
- **S8** has been marked Accepted since PR #73 merged. This change still closes it, so its row has to change to Fixed
  when this change lands, rather than being treated as already settled.
- **Removing bring-your-own-VPC** now meets `SetupCommand`'s rule that networking can't change once an
  installation exists. The design has to say what happens to an installation that was created with
  `--use-existing-vpc`.
- The template already has a `DynamoDbGatewayEndpoint` (`cf-template-core.yaml:182`). Check which
  networking tasks are still needed.
- **Runner-image extensions download during the bake** (`custom-runner-image`, 2026-10-04). An
  installation's extension is an arbitrary AWSTOE document, and its documented use is installing
  agents and tools — which today relies on `ImageBuildSecurityGroup`'s 443/80 internet egress. A
  private build subnet would cut that too. The design has to decide whether the bake keeps egress
  (it is not the measurement host) or extensions lose downloads, and say so in `infra/README.md`'s
  *Extending the runner image*.
