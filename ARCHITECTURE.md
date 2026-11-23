# Secure Inventory Deployment Architecture

Open the editable [AWS deployment diagram](./deployment-architecture.drawio) in diagrams.net (draw.io).

## Deployment flow

The deployment uses one shared ECR repository and ECS cluster, with separate dev and prod services, task definitions, target groups, EFS access points, and data. QA resources may also be part of the target design, but the current workflows automate dev and prod only. A single CloudFront hostname can expose the environments under `/dev/`, `/qa/`, and `/prod/`. The AWS resources and listener rules are being configured manually; verify each actual setting in AWS before enabling production promotion.

```mermaid
flowchart LR
  Repo[("GitHub repository")]
  PR["Pull request to main"]
  Push["Application change pushed to main"]
  Gates["EarlyBird secret scan<br/>Trivy filesystem scan<br/>Maven tests and 60% JaCoCo gate<br/>SonarCloud quality gate"]
  Build["Docker build<br/>Trivy image scan"]
  ECR[("Private ECR<br/>main-commit image tag")]
  Dev["Automatic dev ECS deployment"]
  Promote["Manual prod promotion<br/>approval + digest rescan"]
  Prod["Production ECS service"]
  Secrets[("AWS Secrets Manager")]
  Data[("EFS environment access point<br/>SQLite and uploads")]
  Browser["Browser over HTTPS"]
  CF["CloudFront"]
  ALB["ALB HTTP origin<br/>custom header rules"]
  TG["Matching target group"]
  App["Spring Boot container<br/>port 8080"]

  Repo --> PR --> Gates
  Repo --> Push --> Gates --> Build --> ECR
  ECR --> Dev
  ECR --> Promote --> Prod
  Dev --> App
  Prod --> App
  Secrets -. "task execution role retrieves secret at startup" .-> App
  App <--> Data
  Browser -->|HTTPS| CF -->|HTTP origin| ALB --> TG --> App
```

Pull requests run the quality gates only and do not receive AWS credentials. SonarCloud analysis runs for pushes and same-repository pull requests; fork pull requests skip it because GitHub withholds repository secrets. Merges to `main` build, scan, push a commit-tagged image, and deploy to dev only after the SonarCloud quality gate passes. Production is a separate manual promotion of the selected image tag; the workflow resolves its digest, rescans that digest, and deploys that exact artifact after the `prod` environment's approval rules are satisfied. The diagram shows the intended design, not a claim that every AWS resource is already complete; verify task definitions, services, networks, EFS mounts, secret references, and least-privilege IAM permissions.

## Trivy image scan failure diagnosis

The supplied run scanned image `main-cdd418fde516` and failed in the **Trivy container image scan**. Trivy reported **38 HIGH/CRITICAL vulnerabilities (22 HIGH, 16 CRITICAL)**, all in Java dependencies inside `app/app.jar`; the Ubuntu 26.04 base image and the `pebble` Go binary each reported zero vulnerabilities. The vulnerable versions came from the Spring Boot 3.5.7 dependency set: Spring Boot 3.5.7, Jackson 2.19.2, Tomcat 10.1.48, Spring Framework 6.2.12, Spring Data 3.5.5, Spring Security 6.5.6, and Thymeleaf 3.1.3.

This was not a SonarCloud, AWS authentication, or base-image failure. The container scan is configured with `severity: CRITICAL,HIGH` and `exit-code: '1'`, so any finding at those severities fails the step. The image push is sequenced after that gate; therefore the image was not pushed and the ECS deployment could not run.

The first dependency upgrade still left two critical advisories: CVE-2026-47884 and CVE-2026-47890 in Spring MVC 6.2.19, for which the supplied Trivy database listed Spring Framework 7.0.9 as the fixed version. Spring Boot 3.5 manages Spring Framework 6.2, so upgrading only within the Boot 3.5 line could not clear these findings. The app has now moved to Spring Boot 4.0.10, which manages Spring Framework 7.0.11, Spring Security 7.0.9, Spring Data 4.0.9, Jackson 3.1.8, Tomcat 11.0.27, and Thymeleaf 3.1.7. Maven tests and the coverage gate pass locally. The latest GitHub run scanned the Boot 3.5.16 image and failed on those two MVC advisories; run the workflow again after this Boot 4 update is pushed to verify against GitHub's live Trivy database. The workflow summary reports each HIGH/CRITICAL package/advisory and explains why the gate stopped the push.

## Security controls and limitations

- **Secrets:** Administrator credentials belong in Secrets Manager and are injected by ECS at task start. The task execution role should read only the required secret; the app task role should not have that permission. Secret values must not be in Git, GitHub variables, Docker build arguments, image layers, or logs. The app BCrypt-hashes the initial admin password in SQLite; updating the secret does not rotate an already-created account.
- **CI credentials:** The current GitHub Actions setup uses a dedicated IAM user's long-lived access key as GitHub Actions secrets. This is a compromise because OIDC role assumption was not available in the account. Scope permissions narrowly, restrict workflow and secret administration, never use root keys, and rotate or revoke the key. GitHub OIDC with short-lived role credentials is the preferred design when available.
- **Build gates:** EarlyBird scans tracked application files. Trivy blocks high/critical filesystem and image findings (unfixed vulnerabilities are ignored). Maven tests and JaCoCo enforce the configured 60% line coverage. SonarCloud CI-based analysis waits for the configured quality gate; a failed analysis or gate blocks ECR publishing and ECS deployment.
- **Container:** The Docker image runs as a non-root user. Compose additionally uses a read-only root filesystem, drops Linux capabilities, disables privilege escalation, limits resources, and binds locally. Match these controls in ECS where supported.
- **Network and TLS:** The planned no-domain access path encrypts browser-to-CloudFront traffic with HTTPS, but CloudFront-to-ALB is HTTP. The CloudFront origin-facing prefix list permits traffic from CloudFront generally, not only this distribution; the ALB should also require the per-environment private origin header and use a fixed-response default rule. This is demo-grade, not end-to-end TLS. Do not send sensitive production data or credentials through it. For production, use a domain and TLS from the viewer through to the ALB (or another fully encrypted private origin design).
- **Persistence and availability:** Separate EFS access points keep environment directories apart, but this application uses SQLite. Run only one active task per environment and do not scale out against the same database. EFS-backed SQLite is a constrained demo design; use a managed database such as PostgreSQL for production or multiple replicas.
