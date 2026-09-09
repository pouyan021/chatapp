# GitHub Actions

The CI workflow runs on pushes, pull requests, merge queues, and manual dispatches. It reads
the Java version from `gradle/libs.versions.toml` and uses the Gradle wrapper.

The build runs `./gradlew --no-daemon --stacktrace spotlessCheck build` to check
Palantir Java formatting, run all tests, and package the Spring Boot application.
The Ubuntu runner provides Docker; the existing Testcontainers configuration
starts Cassandra and Grafana LGTM for integration tests, so CI does not need
to start `compose.yaml` or configure database credentials.

Each successful run provides an executable application JAR in the Actions run's
artifacts. Test reports are uploaded even when tests fail. Artifacts are kept
for 14 days. The workflow uses read-only repository permissions and needs no
repository secrets.

To require CI before merging, import `.github/rulesets/default-branch.json` in
GitHub's repository Settings → Rules → Rulesets after CI's first run. The ruleset
requires `Format, test, and package` on the default branch, with the branch up to
date before merging. Committing the JSON file alone does not activate the rule.

If formatting fails, run `./gradlew spotlessApply` locally and commit the changes.

Deployment to a running environment is not configured. It requires a deployment
target and its authentication settings; the JAR artifact can be used as the
input to that deployment.

## Dependency updates

Dependabot checks Gradle, GitHub Actions, and Docker Compose weekly. Minor and
patch updates are grouped within each ecosystem; major updates get separate PRs.
Docker Compose coverage does not update image strings embedded in Java tests.
Images using `latest` still need explicit versions or digests to make updates
reviewable and builds reproducible.

## Qodana

Configure `QODANA_TOKEN` as both an Actions secret and a Dependabot secret in
GitHub Settings → Secrets and variables. GitHub does not expose the existing
Actions secret value for copying; use the token from Qodana when setting both.
Never commit the token to the repository.

Without a token, the workflow reports that the scan was skipped in a warning and
the run summary. Dependabot and fork PR runs disable comments and check
annotations that require a writable GitHub token. The Gradle CI job remains the
required check; a skipped Qodana scan does not establish code quality.

## Local hooks

Enable the tracked pre-push formatting hook once per clone:

```sh
git config --local core.hooksPath .githooks
```
