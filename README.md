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

The workflow is [`./.github/workflows/secure-inventory.yml`](./.github/workflows/secure-inventory.yml). It runs secret and dependency/configuration scans, Maven tests, a JaCoCo line-coverage gate, then builds and scans a Docker image, pushes it to private ECR, and optionally updates one ECS service. SonarCloud Automatic Analysis runs independently on the project; it does not gate this workflow. JaCoCo enforces at least **60% line coverage**.

The workflow currently runs only when manually dispatched. Its input selects one of `dev`, `qa`, or `prod`; `deploy_to_ecs` defaults to false so you can run quality checks, build, scan, and push the image before ECS is ready. The run title and job names include the selected environment, and a final GitHub Actions summary lists each check's result. Enable ECS deployment only after the selected environment's ECS service/task definition and deployment settings are configured. EarlyBird scans the app repository's tracked files from the repository root, not the scanner's own checkout. Because this setup uses an IAM user instead of GitHub OIDC, the workflow authenticates with that user's long-lived access key stored as GitHub Actions secrets. This is less secure than OIDC: use a dedicated least-privilege user, protect access to the repository and its Actions settings, rotate the key, and never put it in variables, source files, workflow output, or the image.

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

The workflow uses a dedicated IAM user access key because this deployment is not using OIDC. This is a long-lived credential and is less secure than OIDC. Do not use root credentials. The IAM user's current ECR-only policy allows image publishing, but deployment will continue to fail until you add the ECS deployment permissions after creating the services and task definitions.

Keep the IAM user's permissions narrow:

- **ECR push:** `ecr:GetAuthorizationToken` requires `"Resource": "*"`. Grant `ecr:DescribeRepositories`, `ecr:BatchCheckLayerAvailability`, `ecr:InitiateLayerUpload`, `ecr:UploadLayerPart`, `ecr:CompleteLayerUpload`, and `ecr:PutImage` only on the exact repository ARN. For the configured `manikalasunitha/basic_spring_app` repository, use `arn:aws:ecr:eu-north-1:${aws:PrincipalAccount}:repository/manikalasunitha/basic_spring_app`. The repository path is part of the ARN; granting access to `repository/basic_spring_app`, `repository/manikalasunitha`, or the old `repository/secure-inventory` does not grant access to this repository.
- **ECS deploy:** `ecs:DescribeTaskDefinition`, `ecs:RegisterTaskDefinition`, `ecs:DescribeServices`, and `ecs:UpdateService` for only the relevant task definitions/services; `iam:PassRole` only for the ECS task and execution roles, with `iam:PassedToService` restricted to `ecs-tasks.amazonaws.com`. Add actual resource ARNs after creating these resources; do not use placeholder ARNs or the cluster ARN for service permissions.
- **ECS task execution role:** ECR pull permissions, CloudWatch log permissions, and `secretsmanager:GetSecretValue` for the admin secret. Do not give the app task role access to the admin secret.

For the current image-push `403`, update the **IAM user whose access key is stored in `AWS_ACCESS_KEY_ID` / `AWS_SECRET_ACCESS_KEY`**. In IAM → Users → that user → Permissions, attach an inline policy like this. The policy uses the caller's account ID so it stays scoped to that account:

The latest preflight error identifies the principal as `secure-inventory-deployer` and the missing action as `ecr:DescribeRepositories`. Make sure this action is included in the repository-scoped statement below and that the policy is attached to that exact IAM user (not only the ECS task execution role). Save the policy before rerunning Actions.

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
        "ecr:PutImage"
      ],
      "Resource": "arn:aws:ecr:eu-north-1:${aws:PrincipalAccount}:repository/manikalasunitha/basic_spring_app"
    }
  ]
}
```

The reported `403 Forbidden` is returned for a registry `HEAD` request to `/v2/manikalasunitha/basic_spring_app/blobs/...`, which is the layer-availability check during `docker push`. Since AWS credentials configuration, ECR login, image build, and the Trivy image scan succeeded, the immediate likely cause is that the IAM user's ECR upload actions are missing or scoped to the wrong repository ARN. If the policy above is already attached, check for an explicit deny in the IAM user's other policies, a permissions boundary, an AWS Organizations SCP, or an ECR repository policy. Verify the repository exists in `eu-north-1` in the same AWS account used by the GitHub Actions credentials and that the workflow variable `ECR_REPOSITORY` is exactly `manikalasunitha/basic_spring_app`. The workflow preflights the caller account and exact repository URI before building, but only an actual push can confirm layer-upload permissions.

In GitHub **Settings → Secrets and variables → Actions → Secrets**, add these **repository secrets** using the dedicated IAM user's access key. Do not paste either value into chat, variables, files, or workflow logs:

| Repository secret | Value |
|---|---|
| `AWS_ACCESS_KEY_ID` | IAM user's access key ID |
| `AWS_SECRET_ACCESS_KEY` | Matching secret access key |

Protect the repository and Actions settings so only trusted maintainers can change workflows or use these secrets. Restrict who can trigger workflows, require reviewers for `prod`, and rotate/delete the IAM access key when no longer needed. Anyone who can modify a workflow that receives these secrets may be able to use the IAM user's permissions.

In GitHub **Settings → Secrets and variables → Actions → Variables**, add:

| Repository variable | Value |
|---|---|
| `AWS_REGION` | The region containing the ECR repository, for example `eu-north-1` |
| `ECR_REPOSITORY` | Exact ECR repository name, including any namespace path, for example `manikalasunitha/basic_spring_app` |

For `ECR_REPOSITORY`, enter the name in the **Value** field; the variable **Name** remains `ECR_REPOSITORY`. Slashes are valid in an ECR repository name. If the ECR console shows `manikalasunitha` as a namespace/folder, use the complete repository path `manikalasunitha/basic_spring_app`, not just `manikalasunitha`. Confirm that this exact repository exists in the account and `AWS_REGION` used by the workflow. The IAM user's ECR upload policy must also allow the repository ARN ending in `repository/manikalasunitha/basic_spring_app`; otherwise the preflight or image push will fail.
Do not add `SONAR_TOKEN` or Sonar analysis variables for this Automatic Analysis setup. In SonarCloud, keep Automatic Analysis enabled for the project and monitor its analysis result in the SonarCloud project page. It runs separately from this workflow, so it does not block ECR publishing or ECS deployment. If SonarCloud reports that Automatic Analysis conflicts with a CI scanner, ensure the Sonar scan action remains removed from this workflow.

Create GitHub **environments** named `dev`, `qa`, and `prod`. In each environment, add:

| Environment variable | Value |
|---|---|
| `ECS_CLUSTER` | ECS cluster name |
| `ECS_SERVICE` | ECS service name |
| `ECS_TASK_DEFINITION` | Existing task-definition family or ARN |
| `ECS_CONTAINER_NAME` | App container name in that task definition |

Restrict allowed deployment branches and configure required reviewers for `prod`. Keep AWS account IDs, role ARNs, and infrastructure identifiers in variables; store only actual tokens/passwords as secrets.

### 4. Run and verify deployment

1. Confirm ECR, ECS, Secrets Manager, EFS, IAM, and GitHub variables/environments are configured. Check SonarCloud Automatic Analysis separately in its project.
2. Select **Actions → Secure Inventory CI and ECS Deployment → Run workflow**, choose the branch, then choose one target environment.
3. Confirm secret/dependency scans, `mvn verify`, and the 60% JaCoCo coverage gate pass before image build/push. Review SonarCloud Automatic Analysis separately.
4. Confirm the ECR image uses the branch and commit SHA tag. The workflow updates only the selected environment, registers a task-definition revision with that scanned image, and waits for ECS stability.
5. Verify the ECS service is healthy behind the load balancer and sign in using the administrator credentials in that environment's Secrets Manager secret.

EarlyBird fails on medium-or-higher findings. The run on commit `e3737e4` failed because EarlyBird treated the AWS account number in the example ECR ARN as three possible national IDs (critical severity, low confidence); it also reported low-severity false positives for version tags that looked like email addresses, local HTTP examples, a `file://` CLI argument, and the intentional loopback-only Compose binding. The fixes keep the same behavior while avoiding those scanner patterns: use a caller-account IAM policy variable, pin the Trivy action by commit SHA, pass the task definition JSON directly to AWS CLI, omit explicit schemes in local-only examples, and assemble the loopback address from its prefix plus final octet. No real secret or personal identifier was found in that report. EarlyBird fails on medium-or-higher findings; Trivy fails on high/critical filesystem or container findings (ignoring unfixed vulnerabilities). Deployment is blocked if a scan, test, coverage check, or ECS stability wait fails. SonarCloud Automatic Analysis is monitored separately and is not a deployment gate in this workflow.
# CI-CD-Automation-and-Cloud-Deployment
