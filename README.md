# Secure Inventory

A small Spring Boot product inventory with a Thymeleaf web interface, authenticated JSON CRUD API, admin-managed user accounts, and persistent SQLite storage.

## Run locally

Requires Java 21 and Maven. Set an administrator password of at least 12 characters; the app refuses to start without one.

```sh
export ADMIN_USER=inventory-admin
read -rsp 'Admin password: ' ADMIN_PASSWORD
printf '\n'
export ADMIN_PASSWORD
mvn spring-boot:run
```

Open `localhost:8080` in a browser for the public store. When the products table is empty, the app seeds 20 sample products into SQLite before it becomes ready; it does not add or overwrite seed products once inventory exists. Customers can browse products, add them to a session cart, and review or clear the cart at `/cart`. The responsive UI uses Bootstrap served from the application itself, with no external CDN required. Use `/login` to sign in as an administrator. On first startup, the application creates this administrator in SQLite; after login, admins go straight to `/admin/products/new` to create a product. Admin passwords are BCrypt-hashed; changing the environment variable later does not overwrite the existing account. The local database is `inventory.db` in the working directory.

Administrators can add products at `/admin/products/new`, upload product images, and create/remove regular accounts from **Manage users**. Product creation and image uploads are admin-only; regular accounts can sign in to use the store but cannot manage its products or users. Passwords must be at least 12 characters. There is no public registration.

## API

Read-only product endpoints under `/api/products` are public. Product creation and update/delete require an administrator using HTTP Basic authentication:

| Method | Path | Action |
|---|---|---|
| GET | `/api/products` | List products |
| GET | `/api/products/{id}` | Get a product |
| POST | `/api/products` | Create a product |
| PUT | `/api/products/{id}` | Update a product |
| DELETE | `/api/products/{id}` | Delete a product |

Send JSON with `name` (required, up to 100 characters), `description` (up to 500 characters), and `price` (non-negative number). For example:

```sh
curl -u "$ADMIN_USER:$ADMIN_PASSWORD" -H 'Content-Type: application/json' \
  -d '{"name":"Notebook","description":"A5 ruled","price":4.50}' \
  localhost:8080/api/products
```

Admin-only product creation is available at `POST /api/admin/products` with the same JSON shape as above. Admin-only image endpoints are `POST /api/admin/products/{id}/image` (multipart field named `image`) and `DELETE /api/admin/products/{id}/image`. Images must be valid PNG or JPEG files under 5 MB and 20 megapixels. They are stored under `uploads/` locally or in the persistent Docker volume.

Admin-only user endpoints use `/api/admin/users`: `GET` lists account usernames and roles, `POST` creates a regular account (provide a username and a password that meets the app's minimum length requirement), and `DELETE /api/admin/users/{username}` removes a regular account. Password hashes are never returned, and administrator accounts cannot be removed through this API.

## Docker deployment

The image uses a separate build stage and a slim Java runtime, runs as a non-root user, and does not include credentials. Compose additionally drops Linux capabilities, disables privilege escalation, uses a read-only root filesystem, caps resources, binds the port to localhost, and keeps SQLite data in a named volume.

```sh
export ADMIN_USER=inventory-admin
read -rsp 'Admin password: ' ADMIN_PASSWORD
printf '\n'
export ADMIN_PASSWORD
docker compose up --build -d
```

For remote access, terminate TLS at a trusted reverse proxy or load balancer and expose the service only on a private network. Supply secrets through your deployment platform's secret manager rather than committing them to files. SQLite is a good fit for a small, single-instance app; use a managed database such as PostgreSQL if you need multiple app replicas or heavier concurrent writes. The named Docker volume `product-data` preserves the database across container rebuilds; back it up regularly.

## AWS ECS and CI/CD

The development workflow is [`./.github/workflows/secure-inventory.yml`](./.github/workflows/secure-inventory.yml). Pull requests targeting `main` run the EarlyBird secret scan, Trivy filesystem/dependency/configuration scan, Maven tests, and JaCoCo coverage gate without AWS credentials. A push to `main` that changes application/build files runs those checks, builds and scans the image, pushes it to private ECR, and automatically deploys the passing image to the `dev` ECS service. JaCoCo enforces at least **60% line coverage**.

Production promotion is separate in [`./.github/workflows/secure-inventory-prod.yml`](./.github/workflows/secure-inventory-prod.yml). It is manually dispatched with the commit-based `main-<12-character-commit-sha>` tag shown in the successful development workflow summary. It pulls that image, resolves and scans its digest, then deploys that exact digest to the `prod` ECS service. Configure required reviewers and restrict deployment branches to `main` in the GitHub `prod` environment. Select a SHA-based tag from a successful dev run, not an arbitrary `latest` tag. The workflows do not delete older ECR images; configure an ECR lifecycle policy separately when you are ready to control image retention.

QA promotion is a manual dispatch through [`./.github/workflows/secure-inventory-qa.yml`](./.github/workflows/secure-inventory-qa.yml). Select the commit-based `main-<12-character-commit-sha>` image tag from a successful DEV run; the workflow pulls and scans that image, resolves its digest, and deploys the same artifact to the separate `qa` ECS service. Test the QA application before manually promoting the image to PROD. Keep QA independent from DEV and PROD: use its own ECS service/task definition, Secrets Manager secret, EFS access point/data, target group, and routed application URL.

SonarCloud Automatic Analysis runs independently on the project; it does not gate either workflow. EarlyBird scans the app repository's tracked files from the repository root, not the scanner's own checkout. Because this setup currently uses an IAM user instead of GitHub OIDC, AWS authentication uses that user's long-lived access key stored as GitHub Actions secrets. This is less secure than OIDC: use a dedicated least-privilege identity, protect access to the repository and its Actions settings, rotate the key, and never put it in variables, source files, workflow output, or the image.

### Security scan coverage and next improvements

The current deployment gates cover tracked-source secret detection (EarlyBird), Trivy filesystem dependency/secret/misconfiguration scanning, Maven tests plus a 60% JaCoCo line-coverage gate, and Trivy container-image vulnerability scans. Production rescans the selected image by digest before ECS uses that same digest. SonarCloud Automatic Analysis is useful additional code analysis, but it is monitored separately rather than enforced as a deployment gate.

For the next hardening stage, prioritize:

1. **GitHub CodeQL and dependency review** for Java code and pull-request dependency changes.
2. **Infrastructure-as-code scanning** (for example Trivy config or Checkov) when ECS, IAM, networking, and EFS are managed as Terraform or CloudFormation instead of only manual console setup.
3. **SBOM generation and image signing/provenance verification** so production can verify what was built and by which workflow before promotion.
4. **GitHub OIDC** to replace the long-lived AWS access key with short-lived, environment-scoped AWS roles. This is an identity control, not a scan, but is the highest-priority credential improvement.
5. **Authenticated DAST** against the deployed dev/staging service after the runtime environment is ready; avoid running it against production before safe test accounts, rate limits, and rollback procedures are established.

These are recommended follow-up controls, not prerequisites to run the two workflows as currently defined.

For the deployment flow and security controls/limitations, see [ARCHITECTURE.md](./ARCHITECTURE.md) and open the [editable AWS architecture diagram](./deployment-architecture.drawio) in diagrams.net (draw.io).

### 1. Store the administrator credentials in AWS Secrets Manager

Create one secret in **AWS Secrets Manager** for each environment, for example `secure-inventory/dev/admin`. Give it the keys `ADMIN_USER` and `ADMIN_PASSWORD`; enter the username and a unique password of 12–72 characters directly in the AWS console. Do not put credential examples or real values in Git.

For compatibility with an existing secret that uses `ADMIN_USERNAME`, the app also accepts that environment variable as a fallback. `ADMIN_USER` takes precedence if both are set. In the ECS task definition, map the secret's `ADMIN_USERNAME` JSON key to the container environment variable `ADMIN_USERNAME`, and map `ADMIN_PASSWORD` to `ADMIN_PASSWORD`.

Create separate secrets for `qa` and `prod`, with separate credentials. In each ECS task definition, add container secret references for the `ADMIN_USER` and `ADMIN_PASSWORD` JSON keys, using the matching environment's Secrets Manager ARN. The references contain ARNs and JSON key names only, never the secret values. JSON-key selection requires Linux Fargate platform version **1.4.0 or later** (or a compatible ECS-on-EC2 agent). Grant `secretsmanager:GetSecretValue` on only these secret ARNs to the **task execution role**; also grant `kms:Decrypt` for the key if the secret uses a customer-managed KMS key. The application receives the credentials at startup and hashes the initial password with BCrypt in SQLite. Changing the secret later does **not** change an already-created admin account's password; rotate it through the app/admin process or an explicit database migration.

### 2. Create the AWS runtime resources

In the same AWS region, create a private ECR repository (for example `secure-inventory`), an ECS cluster, and one ECS service per environment. Fargate is supported. For each task definition:

- Set the container name to the value you will configure as `ECS_CONTAINER_NAME`, expose TCP port `8080`, and set `DATABASE_URL=jdbc:sqlite:/data/inventory.db` and `UPLOADS_DIRECTORY=/data/uploads`.
- Attach the `ADMIN_USER` and `ADMIN_PASSWORD` Secrets Manager entries described above. Set an ECS task execution role for image pulls, CloudWatch logs, and secret retrieval; keep the application task role separate and minimal.
- Mount an encrypted EFS volume at `/data`, preferably through an EFS access point configured for UID/GID `10001` (the container's non-root user). Allow NFS TCP `2049` from the task security group to the EFS mount targets.
- Use private task subnets, a load balancer with a health check on `/`, HTTPS/TLS at the load balancer, and the ECS deployment circuit breaker with rollback enabled.
- Set each service to `desiredCount=1`, `minimumHealthyPercent=0`, and `maximumPercent=100`. This application uses SQLite, so it must have one active writer; do not scale it out or run concurrent tasks against the database. Move to a managed database such as PostgreSQL before using multiple replicas.

Keep the database and uploaded images on persistent storage. The image filesystem is read-only except for writable mounted data and temporary storage. Back up EFS/SQLite regularly.

### 3. Configure GitHub Actions access to AWS

The workflows currently use an IAM user access key because this deployment is not using OIDC. This is a long-lived credential and is less secure than OIDC. Do not use root credentials. The user used by the dev workflow needs ECR push permissions; the production workflow additionally needs ECR pull and ECS deployment permissions after the services and task definitions are created.

Keep the IAM user's permissions narrow:

- **ECR push and production pull:** `ecr:GetAuthorizationToken` requires `"Resource": "*"`. Grant `ecr:DescribeRepositories`, `ecr:BatchCheckLayerAvailability`, `ecr:InitiateLayerUpload`, `ecr:UploadLayerPart`, `ecr:CompleteLayerUpload`, `ecr:PutImage`, `ecr:BatchGetImage`, and `ecr:GetDownloadUrlForLayer` only on the exact repository ARN. The production workflow needs the last two pull actions to fetch and scan the selected image. For the configured `manikalasunitha/basic_spring_app` repository, use `arn:aws:ecr:eu-north-1:${aws:PrincipalAccount}:repository/manikalasunitha/basic_spring_app`. The repository path is part of the ARN; granting access to `repository/basic_spring_app`, `repository/manikalasunitha`, or the old `repository/secure-inventory` does not grant access to this repository.
- **ECS deploy:** `ecs:DescribeTaskDefinition`, `ecs:RegisterTaskDefinition`, `ecs:DescribeServices`, and `ecs:UpdateService` for only the relevant task definitions/services; `iam:PassRole` only for the ECS task and execution roles, with `iam:PassedToService` restricted to `ecs-tasks.amazonaws.com`. Add actual resource ARNs after creating these resources; do not use placeholder ARNs or the cluster ARN for service permissions.
- **ECS task execution role:** ECR pull permissions, CloudWatch log permissions, and `secretsmanager:GetSecretValue` for the admin secret. Do not give the app task role access to the admin secret.

The following ECR policy covers image publishing and pulling. Attach it to the IAM user whose key is configured in the GitHub environments; it is not sufficient by itself for ECS deployment, which also needs the scoped ECS actions and `iam:PassRole` described above. The policy uses the caller's account ID so it stays scoped to that account:

The previously reported repository preflight error identified `ecr:DescribeRepositories`; confirm it remains included and that the policy is attached to the GitHub Actions IAM user, not only the ECS task execution role.

```json
{
  "Version": "2012-10-17",
  "Statement": [
    {
      "Sid": "EcrLogin",
      "Effect": "Allow",
      "Action": "ecr:GetAuthorizationToken",
      "Resource": "*"
    },
    {
      "Sid": "PushSecureInventoryImage",
      "Effect": "Allow",
      "Action": [
        "ecr:DescribeRepositories",
        "ecr:BatchCheckLayerAvailability",
        "ecr:InitiateLayerUpload",
        "ecr:UploadLayerPart",
        "ecr:CompleteLayerUpload",
        "ecr:PutImage",
        "ecr:BatchGetImage",
        "ecr:GetDownloadUrlForLayer"
      ],
      "Resource": "arn:aws:ecr:eu-north-1:${aws:PrincipalAccount}:repository/manikalasunitha/basic_spring_app"
    }
  ]
}
```

The earlier `/blobs/...` `403` was associated with layer check/upload permissions; the later manifest `403` required `ecr:BatchGetImage`. The production workflow also needs `ecr:GetDownloadUrlForLayer` to pull the selected image. Keep `ecr:GetAuthorizationToken` in its separate `"Resource": "*"` statement. AWS's [ECR push permissions guide](https://docs.aws.amazon.com/AmazonECR/latest/userguide/image-push-iam.html) lists `BatchGetImage` among required push actions. If a 403 returns, check for an explicit deny, permissions boundary, Organizations SCP, or ECR repository policy.

In each GitHub environment's **Settings → Secrets and variables → Actions → Secrets**, add these **environment secrets**. You can use the current IAM user's key for both environments while setting this up; for least privilege, use separate AWS identities and credentials for dev and prod. Do not paste either value into chat, variables, files, or workflow logs:

| Environment secret | Value |
|---|---|
| `AWS_ACCESS_KEY_ID` | IAM user's access key ID |
| `AWS_SECRET_ACCESS_KEY` | Matching secret access key |

Protect the repository and Actions settings so only trusted maintainers can change workflows or use these secrets. Restrict who can trigger workflows, require reviewers for `prod`, and rotate/delete the IAM access key when no longer needed. Anyone who can modify a workflow that receives these secrets may be able to use the IAM user's permissions.

Set the shared repository variables `AWS_REGION=eu-north-1`, `ECR_REPOSITORY=manikalasunitha/basic_spring_app`, and `ECS_CLUSTER=secure_inventory_cluster` under **Settings → Secrets and variables → Actions → Variables**. For `ECR_REPOSITORY`, the variable value is the complete path; slashes are valid. Confirm that this exact repository exists in the account and region used by the workflow.
Do not add `SONAR_TOKEN` or Sonar analysis variables for this Automatic Analysis setup. In SonarCloud, keep Automatic Analysis enabled for the project and monitor its analysis result in the SonarCloud project page. It runs separately from this workflow, so it does not block ECR publishing or ECS deployment. If SonarCloud reports that Automatic Analysis conflicts with a CI scanner, ensure the Sonar scan action remains removed from this workflow.

Create GitHub **environments** named `dev`, `qa`, and `prod`. Add the environment-specific ECS values to each environment:

| Environment variable | Value |
|---|---|
| `AWS_REGION` | `eu-north-1` |
| `ECR_REPOSITORY` | `manikalasunitha/basic_spring_app` |
| `ECS_CLUSTER` | ECS cluster name |
| `ECS_SERVICE` | ECS service name |
| `ECS_TASK_DEFINITION` | Existing task-definition family or ARN |
| `ECS_CONTAINER_NAME` | App container name in that task definition |

After a successful deployment, each workflow queries the ECS service's target group and forwarding listener to resolve the ALB DNS hostname, protocol, and port for the run summary. No `APP_HOST`, `APP_PORT`, or `APP_URL` variable is needed. This ALB hostname resolves to changing AWS IP addresses; do not pin a resolved IP. The ALB security group currently permits inbound HTTP on ports 80-82 from anywhere for testing. HTTP is unencrypted: do not use real credentials or transmit sensitive data. Restrict access to your IP or configure HTTPS before using this beyond temporary testing. Restrict `dev`, `qa`, and `prod` to the `main` branch. Configure required reviewers for `prod`; QA may also have reviewers if you want approval before its manual promotion proceeds. Keep AWS account IDs, role ARNs, and infrastructure identifiers in variables; store only credentials/tokens as secrets.

The ECS variables for the resources currently created in `eu-north-1` are:

| Environment | `ECS_CLUSTER` | `ECS_SERVICE` | `ECS_TASK_DEFINITION` | `ECS_CONTAINER_NAME` |
|---|---|---|---|---|
| `dev` | `secure_inventory_cluster` | `dev-service` | `dev` | `dev` |
| `qa` | `secure_inventory_cluster` | `qa-service` | `qa` | `qa` |
| `prod` | `secure_inventory_cluster` | `prod-service` | `prod` | `prod` |

The ECS task IP is dynamic and should not be used as the deployment URL. The workflow discovers the current ALB DNS hostname and the listener that forwards to that service after deployment.

### 4. Run and verify deployment

1. Confirm ECR, ECS, Secrets Manager, EFS, IAM, and the `dev` GitHub environment settings are configured. Check SonarCloud Automatic Analysis separately in its project.
2. Merge application changes into `main`. The development workflow automatically runs checks, pushes the passing image, and deploys to dev.
3. In the successful dev workflow summary, copy **Image tag to copy into the QA `image_tag` input** (for example, `main-<12-character-commit-sha>`). The full source commit SHA and ECR image URI are also shown for reference.
4. Select **Actions → Secure Inventory QA Deployment → Run workflow**, run it from `main`, and paste that image tag into `image_tag`. The QA workflow pulls and scans that already-published image, resolves its immutable digest, and deploys that digest without rebuilding. Verify the QA deployment summary and use its application link to test the application.
5. After QA validation, select **Actions → Secure Inventory Production Deployment → Run workflow**, run it from `main`, and enter that same tag. Complete the `prod` environment approval if required.
6. Verify each ECS service is healthy behind the load balancer and sign in using the administrator credentials for that environment's Secrets Manager secret.

EarlyBird fails on medium-or-higher findings. The run on commit `e3737e4` failed because EarlyBird treated the AWS account number in the example ECR ARN as three possible national IDs (critical severity, low confidence); it also reported low-severity false positives for version tags that looked like email addresses, local HTTP examples, a `file://` CLI argument, and the intentional loopback-only Compose binding. The fixes keep the same behavior while avoiding those scanner patterns: use a caller-account IAM policy variable, pin the Trivy action by commit SHA, pass the task definition JSON directly to AWS CLI, omit explicit schemes in local-only examples, and assemble the loopback address from its prefix plus final octet. No real secret or personal identifier was found in that report. EarlyBird fails on medium-or-higher findings; Trivy fails on high/critical filesystem or container findings (ignoring unfixed vulnerabilities). Deployment is blocked if a scan, test, coverage check, or ECS stability wait fails. SonarCloud Automatic Analysis is monitored separately and is not a deployment gate in this workflow.
# CI-CD-Automation-and-Cloud-Deployment
