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

Open [http://localhost:8080](http://localhost:8080) for the public store. When the products table is empty, the app seeds 20 sample products into SQLite before it becomes ready; it does not add or overwrite seed products once inventory exists. Customers can browse products, add them to a session cart, and review or clear the cart at `/cart`. The responsive UI uses Bootstrap served from the application itself, with no external CDN required. Use `/login` to sign in as an administrator. On first startup, the application creates this administrator in SQLite; after login, admins go straight to `/admin/products/new` to create a product. Admin passwords are BCrypt-hashed; changing the environment variable later does not overwrite the existing account. The local database is `inventory.db` in the working directory.

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
  http://localhost:8080/api/products
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

The workflow is [`./.github/workflows/secure-inventory.yml`](./.github/workflows/secure-inventory.yml). It runs secret and dependency/configuration scans, Maven tests, a JaCoCo line-coverage gate, SonarQube analysis and quality-gate check, then builds and scans a Docker image, pushes it to private ECR, and updates one ECS service. All quality gates must pass before publishing or deploying. JaCoCo enforces at least **60% line coverage**.

The workflow currently runs only when manually dispatched. Its input selects one of `dev`, `qa`, or `prod`; `deploy_to_ecs` defaults to false so you can run quality checks, build, scan, and push the image before ECS is ready. Enable it only after the selected environment's ECS service/task definition and deployment settings are configured. EarlyBird scans the app repository's tracked files from the repository root, not the scanner's own checkout. Because this setup uses an IAM user instead of GitHub OIDC, the workflow authenticates with that user's long-lived access key stored as GitHub Actions secrets. This is less secure than OIDC: use a dedicated least-privilege user, protect access to the repository and its Actions settings, rotate the key, and never put it in variables, source files, workflow output, or the image.

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

- **ECR push:** `ecr:GetAuthorizationToken` and image upload actions (`BatchCheckLayerAvailability`, `InitiateLayerUpload`, `UploadLayerPart`, `CompleteLayerUpload`, `PutImage`) limited to the `secure-inventory` repository.
- **ECS deploy:** `ecs:DescribeTaskDefinition`, `ecs:RegisterTaskDefinition`, `ecs:DescribeServices`, and `ecs:UpdateService` for only the relevant task definitions/services; `iam:PassRole` only for the ECS task and execution roles, with `iam:PassedToService` restricted to `ecs-tasks.amazonaws.com`. Add actual resource ARNs after creating these resources; do not use placeholder ARNs or the cluster ARN for service permissions.
- **ECS task execution role:** ECR pull permissions, CloudWatch log permissions, and `secretsmanager:GetSecretValue` for the admin secret. Do not give the app task role access to the admin secret.

In GitHub **Settings → Secrets and variables → Actions → Secrets**, add these **repository secrets** using the dedicated IAM user's access key. Do not paste either value into chat, variables, files, or workflow logs:

| Repository secret | Value |
|---|---|
| `AWS_ACCESS_KEY_ID` | IAM user's access key ID |
| `AWS_SECRET_ACCESS_KEY` | Matching secret access key |

Protect the repository and Actions settings so only trusted maintainers can change workflows or use these secrets. Restrict who can trigger workflows, require reviewers for `prod`, and rotate/delete the IAM access key when no longer needed. Anyone who can modify a workflow that receives these secrets may be able to use the IAM user's permissions.

In GitHub **Settings → Secrets and variables → Actions → Variables**, add:

| Repository variable | Value |
|---|---|
| `AWS_REGION` | AWS region, for example `eu-north-1` |
| `ECR_REPOSITORY` | ECR repository name, for example `secure-inventory` |
| `SONAR_PROJECT_KEY` | Project key from SonarQube |
| `SONAR_HOST_URL` | SonarQube URL; omit to use `https://sonarcloud.io` |
| `SONAR_ORGANIZATION` | Required for SonarQube Cloud; omit for self-hosted SonarQube |

Add the **repository secret** `SONAR_TOKEN` using a SonarQube user/project analysis token. The SonarQube server must be reachable by the GitHub-hosted runner; for a private server, use an appropriately network-connected/self-hosted runner or securely expose the server.

In SonarQube, create the project and assign a quality gate to it. Use the exact project key as `SONAR_PROJECT_KEY`; create a token with permission to analyze that project and save it as `SONAR_TOKEN`. For SonarQube Cloud, set `SONAR_ORGANIZATION` and leave `SONAR_HOST_URL` unset (or set it to `https://sonarcloud.io`). For a self-hosted server, set `SONAR_HOST_URL` to its HTTPS URL and leave `SONAR_ORGANIZATION` unset. The workflow imports `target/site/jacoco/jacoco.xml` and waits for the assigned gate; configure the gate's conditions in SonarQube so a failed quality policy blocks deployment.

Create GitHub **environments** named `dev`, `qa`, and `prod`. In each environment, add:

| Environment variable | Value |
|---|---|
| `ECS_CLUSTER` | ECS cluster name |
| `ECS_SERVICE` | ECS service name |
| `ECS_TASK_DEFINITION` | Existing task-definition family or ARN |
| `ECS_CONTAINER_NAME` | App container name in that task definition |

Restrict allowed deployment branches and configure required reviewers for `prod`. Keep AWS account IDs, role ARNs, and infrastructure identifiers in variables; store only actual tokens/passwords as secrets.

### 4. Run and verify deployment

1. Confirm ECR, ECS, Secrets Manager, EFS, IAM, GitHub variables/environments, and SonarQube project/token are configured.
2. Select **Actions → Secure Inventory CI and ECS Deployment → Run workflow**, choose the branch, then choose one target environment.
3. Confirm secret/dependency scans, `mvn verify`, 60% JaCoCo coverage, and the SonarQube quality gate all pass before image build/push.
4. Confirm the ECR image uses the branch and commit SHA tag. The workflow updates only the selected environment, registers a task-definition revision with that scanned image, and waits for ECS stability.
5. Verify the ECS service is healthy behind the load balancer and sign in using the administrator credentials in that environment's Secrets Manager secret.

EarlyBird fails on medium-or-higher findings. Trivy fails on high/critical filesystem or container findings (ignoring unfixed vulnerabilities). Deployment is blocked if a scan, test, coverage check, SonarQube quality gate, or ECS stability wait fails.
# CI-CD-Automation-and-Cloud-Deployment
