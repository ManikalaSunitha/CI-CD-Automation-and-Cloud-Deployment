# Secure Inventory

A small Spring Boot product inventory with a Thymeleaf web interface, authenticated JSON CRUD API, admin-managed user accounts, and persistent SQLite storage.

## Run locally

Requires Java 21 and Maven. Set an administrator password of at least 12 characters; the app refuses to start without one.

```sh
export ADMIN_USER=admin
export ADMIN_PASSWORD='choose-a-strong-password-at-least-12-characters'
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

Admin-only user endpoints use `/api/admin/users`: `GET` lists account usernames and roles, `POST` creates a regular account (`{"username":"teammate","password":"at-least-12-characters"}`), and `DELETE /api/admin/users/{username}` removes a regular account. Password hashes are never returned, and administrator accounts cannot be removed through this API.

## Docker deployment

The image uses a separate build stage and a slim Java runtime, runs as a non-root user, and does not include credentials. Compose additionally drops Linux capabilities, disables privilege escalation, uses a read-only root filesystem, caps resources, binds the port to localhost, and keeps SQLite data in a named volume.

```sh
export ADMIN_USER=admin
export ADMIN_PASSWORD='choose-a-strong-deployment-password'
docker compose up --build -d
```

For remote access, terminate TLS at a trusted reverse proxy or load balancer and expose the service only on a private network. Supply secrets through your deployment platform's secret manager rather than committing them to files. SQLite is a good fit for a small, single-instance app; use a managed database such as PostgreSQL if you need multiple app replicas or heavier concurrent writes. The named Docker volume `product-data` preserves the database across container rebuilds; back it up regularly.

## AWS ECS and CI/CD

The single repository workflow is [`../.github/workflows/secure-inventory.yml`](../.github/workflows/secure-inventory.yml). It runs EarlyBird, Trivy filesystem checks, Maven tests, a JaCoCo line-coverage gate, SonarQube Cloud's quality gate, an ECR image scan, then pushes to private Amazon ECR and updates ECS. Each stage depends on the preceding gates, so a failed scan, test, coverage check, or SonarQube gate prevents image publication and deployment. The minimum JaCoCo line coverage is **60%** and is configured in `pom.xml`.

The 20 bundled product photos are downloaded from [Unsplash](https://unsplash.com/license), cropped to 800×800 JPEGs, and packaged under `src/main/resources/product-images/`. On an empty database, startup seeds 20 products and copies the corresponding photos to the configured uploads directory. On existing databases, it adds photos only to matching sample products that do not already have one; custom products and existing images are left unchanged. Images are served by the app and persist with SQLite when the uploads directory is on persistent storage.

### 1. Configure ECS and persistent storage

Create a private ECR repository and an ECS cluster/service (Fargate is supported). Configure the service with:

- One ECS service per GitHub environment (`dev`, `qa`, and `prod`), each configured with `desiredCount=1`; the workflow checks this before deployment and refuses services configured with another count. Set `minimumHealthyPercent=0` and `maximumPercent=100` to avoid concurrent writers to SQLite during rolling deployments.
- A task definition container named to match `ECS_CONTAINER_NAME`, listening on port `8080`, with `DATABASE_URL=jdbc:sqlite:/data/inventory.db` and `UPLOADS_DIRECTORY=/data/uploads`.
- An encrypted EFS volume mounted at `/data`, using an access point with UID/GID `10001`. SQLite requires a single active application writer; do not scale this service to multiple tasks. Use PostgreSQL if you need multiple replicas.
- A load balancer health check on `/`, TLS at the load balancer, private task subnets, and the ECS deployment circuit breaker with rollback enabled.
- The task definition's `secrets` entries for `ADMIN_USER` and `ADMIN_PASSWORD`, sourced from the environment's SSM `SecureString` parameters. Grant the ECS **task execution role** `ssm:GetParameters` for only those parameter ARNs (and `kms:Decrypt` only if you use a customer-managed KMS key).
- Task execution role permissions to pull from the private ECR repository and send logs to CloudWatch.

Create two SSM parameters per environment (`dev`, `qa`, `prod`): `/secure-inventory/<environment>/ADMIN_USER` and `/secure-inventory/<environment>/ADMIN_PASSWORD`. Use **SecureString** and enter the password directly in the AWS Console. The first successful app startup stores a BCrypt hash in SQLite; changing the SSM parameter later does not rotate an existing admin password.

### 2. Configure GitHub OIDC and workflow variables

Configure an AWS IAM OIDC provider for `token.actions.githubusercontent.com`. Create least-privilege IAM roles trusted only for this GitHub repository and the required branch/environment subjects; do not store long-lived AWS access keys in GitHub. Use separate roles for ECR publishing and ECS deployment:

- The ECR role needs `ecr:GetAuthorizationToken` and image upload actions limited to the target repository.
- Each ECS deployment role needs `ecs:DescribeTaskDefinition`, `ecs:RegisterTaskDefinition`, `ecs:UpdateService`, and `ecs:DescribeServices` for its environment, plus `iam:PassRole` only for that cluster's task and execution roles.

Add these **repository variables** under **Settings → Secrets and variables → Actions → Variables**:

| Variable | Example |
|---|---|
| `AWS_REGION` | `us-east-1` |
| `AWS_ECR_ROLE_ARN` | IAM role ARN allowed to publish to ECR |
| `ECR_REPOSITORY` | `secure-inventory` |
| `SONAR_PROJECT_KEY` | SonarQube Cloud project key |
| `SONAR_ORGANIZATION` | SonarQube Cloud organization key |

Add the **repository secret** `SONAR_TOKEN` for SonarQube Cloud. Create GitHub **environments** named `dev`, `qa`, and `prod`; add these variables to each:

| Variable | Value |
|---|---|
| `AWS_ECS_ROLE_ARN` | Environment-specific ECS deploy role ARN |
| `ECS_CLUSTER` | ECS cluster name |
| `ECS_SERVICE` | ECS service name |
| `ECS_TASK_DEFINITION` | Existing task-definition family or ARN |
| `ECS_CONTAINER_NAME` | App container name in that task definition |

In the ECS task definition, configure SSM secret ARNs for the matching environment. Protect the GitHub `prod` environment with required reviewers and restrict its deployment branches.

### 3. Run and understand the workflow

- Run **Actions → Secure Inventory CI and ECS Deployment → Run workflow**. GitHub lets you select the source branch, then the workflow input selects exactly one environment (`dev`, `qa`, or `prod`). There are no automatic push deployments.
- The selected branch runs every quality/security gate before Docker builds the image. Only the successfully scanned image is pushed to private ECR, then the selected environment's ECS service is updated. No other environment is changed.
- EarlyBird fails on medium-or-higher findings. Trivy fails on HIGH/CRITICAL dependency, secret, configuration, or container findings (ignoring unfixed vulnerabilities). Maven tests and JaCoCo enforce coverage; SonarQube waits for its quality gate.
- ECS registers a new task-definition revision with the exact scanned ECR image tag, updates the service, and waits for ECS to report the service stable. A failed deployment or stability check fails the workflow.
# CI-CD-Automation-and-Cloud-Deployment
