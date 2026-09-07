# direct-transaction — the corporate transaction path: maker/approver/releaser workflow,
# the pending-task inbox and the transfers themselves.
#
# Built from source by CI: the pipeline clones this repository and builds this file, so the
# build stage below must be able to produce the jar on its own. Nothing here expects a jar
# handed in from a developer machine.
#
# This service owns the workflow tables (TRX_TASK and friends) and its Flyway migrations run
# at boot against its own history table, flyway_schema_history_transaction. Nothing is needed
# here for that — the migrations travel inside the jar on the classpath under db/migration —
# but it does mean the first container up against a fresh database does schema work before
# it serves.

# ---------- build ----------
FROM maven:3.9-eclipse-temurin-25 AS build

WORKDIR /workspace

# Manifest first so the dependency layer is cached until the pom actually changes. Source
# edits — which are most edits — then reuse the resolved dependency layer instead of
# re-downloading the world.
COPY pom.xml .
RUN mvn -B -q dependency:go-offline

COPY src ./src
RUN mvn -B -q package -DskipTests

# ---------- runtime ----------
# Kept deliberately in step with infra/direct-infra/ship/Dockerfile.java, which is the
# runtime image the OpenShift deployment runs today: same base, same non-root user, same
# entrypoint. If the two drift, the same jar behaves differently depending on which
# pipeline built it, and the difference only shows up under the restricted-v2 SCC.
FROM eclipse-temurin:25-jre

# Never run as root: a JVM does not need it, and an image that can only run as root is one
# OpenShift's restricted SCC will refuse outright. The user is named for the service rather
# than a generic "app" so a stray file's owner still names the service it came from. The
# uid/gid are 1001 by convention only — OpenShift overrides the uid at runtime anyway, and
# the numbers matter solely to the filesystem inside the image.
RUN groupadd --system --gid 1001 transaction \
 && useradd --system --uid 1001 --gid transaction --home /app transaction
WORKDIR /app

# --chown on the COPY, never a separate `RUN chown -R /app`. chown rewrites every file it
# touches, so a RUN would store the whole jar a SECOND time in its own layer — measured at
# 131MB duplicated per service in ship/Dockerfile.java. Setting the owner as the file lands
# costs nothing.
COPY --chown=transaction:transaction --from=build /workspace/target/*.jar /app/app.jar
USER transaction

# 5244, matching server.port in src/main/resources/application.yml and the OCP manifests.
EXPOSE 5244

# exec form: the JVM becomes PID 1 and receives SIGTERM directly, so Spring's graceful
# shutdown (server.shutdown: graceful) actually runs instead of the container being killed
# after the stop timeout — which matters most here, where a request in flight is a customer's
# money moving.
ENTRYPOINT ["java", "-jar", "/app/app.jar"]
