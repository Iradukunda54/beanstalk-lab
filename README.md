# Elastic Beanstalk Lab — Java (Spring Boot) + S3-based Deploys + GitHub Actions CI/CD + DynamoDB

A Spring Boot web app deployed to AWS Elastic Beanstalk, where every release —
initial and subsequent — is a versioned ZIP source bundle stored in S3, and
where the app demonstrates a real backend dependency by incrementing a visit
counter in DynamoDB on every request.

**Live URL:** http://beanstalk-lab-env.eba-t5gnmuti.eu-west-1.elasticbeanstalk.com/

## Architecture

```
 GitHub push (main)
        |
        v
 GitHub Actions (OIDC -> AWS, no long-lived keys)
   1. mvn package               -> beanstalk-app.jar
   2. zip jar + Procfile        -> vN-<sha>.zip
   3. aws s3 cp                 -> s3://beanstalk-lab-artifacts-.../beanstalk-lab/
        |
        v
 aws elasticbeanstalk create-application-version  (source bundle = S3 object)
        |
        v
 aws elasticbeanstalk update-environment  (deploy new version-label)
        |
        v
 Elastic Beanstalk environment (Corretto 17 / Amazon Linux 2023, SingleInstance)
   - EC2 instance profile: aws-elasticbeanstalk-ec2-role
       -> AWSElasticBeanstalkWebTier + inline DynamoDB policy scoped to one table
   - Service role: aws-elasticbeanstalk-service-role
       -> AWSElasticBeanstalkEnhancedHealth + managed updates
   - App reads DDB_TABLE_NAME / AWS_REGION / APP_VERSION as EB environment
     variables (Configuration > Software), not from files in the bundle
        |
        v
 Amazon DynamoDB table: beanstalk-lab-visits (single item, atomic counter)
```

The **initial** deployment used this exact same S3 -> create-application-version
-> create-environment path, run manually once via the AWS CLI, before any CI/CD
existed — so the manual and automated paths are architecturally identical, only
the trigger differs (a human running commands once vs. GitHub Actions running
them on every push).

## Repository layout

```
src/main/java/.../BeanstalkAppApplication.java   Spring Boot entry point
src/main/java/.../AppController.java             / and /health endpoints
src/main/java/.../VisitCounterService.java       DynamoDB UpdateItem call
src/main/resources/application.properties        reads PORT / DDB_TABLE_NAME /
                                                  AWS_REGION / APP_VERSION from env
Procfile                                          `web: java -jar beanstalk-app.jar`
                                                  (Beanstalk Java SE platform contract)
pom.xml                                           Maven build, Spring Boot 3.3,
                                                  AWS SDK v2 (dynamodb)
.github/workflows/deploy.yml                      build -> S3 -> EB CI/CD pipeline
```

## Application endpoints

- `GET /` — returns JSON confirming deployment, the running version label, and
  (if DynamoDB is configured) the current visit count, incrementing it on each
  call:
  ```json
  {
    "message": "Deployed successfully via Elastic Beanstalk",
    "version": "v7-a1b2c3d",
    "timestamp": "2026-09-16T17:41:58Z",
    "visitCount": 42,
    "dynamoDbConnected": true
  }
  ```
- `GET /health` — lightweight liveness check (used as the Beanstalk health
  check path so `/` traffic isn't inflated by health pings).

## External service integration (DynamoDB)

- Table `beanstalk-lab-visits` (on-demand billing, partition key `counterId`).
- The app never hardcodes the table name or region — both come from Beanstalk
  environment variables (`DDB_TABLE_NAME`, `AWS_REGION`), settable/visible
  under **Configuration > Software** in the Beanstalk console.
- Credentials are never embedded: the EC2 instance profile
  (`aws-elasticbeanstalk-ec2-role`) carries an inline policy granting only
  `dynamodb:GetItem/PutItem/UpdateItem` on this one table's ARN.

## CI/CD (GitHub Actions)

`.github/workflows/deploy.yml` runs on every push to `main`:

1. Builds the jar with Maven.
2. Packages `beanstalk-app.jar` + `Procfile` into a uniquely-labeled ZIP
   (`v<run_number>-<short-sha>.zip`).
3. Authenticates to AWS via **OIDC** (`aws-actions/configure-aws-credentials`
   assuming `github-actions-beanstalk-lab-deploy`) — no static AWS keys stored
   in GitHub. The role's trust policy pins the `sub` claim to this exact repo
   and branch. Note: GitHub's OIDC token now embeds immutable owner/repo
   database IDs in `sub`
   (`repo:Iradukunda54@267279209/beanstalk-lab@1373431449:ref:refs/heads/main`)
   rather than plain names — the trust condition matches that exact form, not
   `repo:OWNER/REPO:ref:...`.
4. Uploads the ZIP to S3.
5. Creates a new Elastic Beanstalk application version from that S3 object.
6. Updates the environment to the new version label and sets `APP_VERSION` as
   an environment variable so the running app reports the version it's on.
7. Waits for `environment-updated` and fails the job if the resulting health
   isn't Green/Yellow or the deployed version doesn't match — so a broken
   deploy shows up as a red GitHub Actions run, not a silent stale endpoint.

Security/workflow hardening:
- Third-party actions pinned to commit SHA, not floating tags.
- `permissions: { id-token: write, contents: read }` — least privilege.
- `concurrency` group prevents two overlapping deploys from racing each other.
- No long-lived AWS keys anywhere: the role is only assumable via OIDC from
  this exact repo + branch (see the `sub` condition above).
- The deploy role's own inline policy limits S3 access to this one artifact
  prefix. Elastic Beanstalk permissions use the AWS-managed
  `AdministratorAccess-AWSElasticBeanstalk` policy — `update-environment`
  drives CloudFormation/EC2/Auto Scaling/ELB changes under the hood, and
  hand-scoping every one of those actions (stack-specific `cloudformation:*`,
  per-ASG `autoscaling:*`, etc.) is what AWS's own docs recommend against for
  exactly this reason. It's still scoped to Elastic Beanstalk's own managed
  policy, not `AdministratorAccess`.

## One-time AWS setup (already applied to this account, kept here for reference)

```bash
# Roles (aws-elasticbeanstalk-ec2-role / -service-role are the EB defaults;
# only the DynamoDB inline policy below is lab-specific)
aws iam put-role-policy --role-name aws-elasticbeanstalk-ec2-role \
  --policy-name beanstalk-lab-visits-ddb-access \
  --policy-document file://ddb-policy.json

# DynamoDB table
aws dynamodb create-table --table-name beanstalk-lab-visits \
  --attribute-definitions AttributeName=counterId,AttributeType=S \
  --key-schema AttributeName=counterId,KeyType=HASH \
  --billing-mode PAY_PER_REQUEST

# S3 bucket for source bundles
aws s3 mb s3://beanstalk-lab-artifacts-447558491229

# Application + initial manual deployment (S3 source bundle, not GitHub)
aws elasticbeanstalk create-application --application-name beanstalk-lab
aws s3 cp target/beanstalk-app.jar+Procfile.zip s3://beanstalk-lab-artifacts-447558491229/beanstalk-lab/v1-initial-manual.zip
aws elasticbeanstalk create-application-version --application-name beanstalk-lab \
  --version-label v1-initial-manual \
  --source-bundle S3Bucket=beanstalk-lab-artifacts-447558491229,S3Key=beanstalk-lab/v1-initial-manual.zip
aws elasticbeanstalk create-environment --application-name beanstalk-lab \
  --environment-name beanstalk-lab-env \
  --solution-stack-name "64bit Amazon Linux 2023 v4.12.8 running Corretto 17" \
  --version-label v1-initial-manual \
  --option-settings \
    Namespace=aws:autoscaling:launchconfiguration,OptionName=IamInstanceProfile,Value=aws-elasticbeanstalk-ec2-role \
    Namespace=aws:elasticbeanstalk:environment,OptionName=ServiceRole,Value=aws-elasticbeanstalk-service-role \
    Namespace=aws:elasticbeanstalk:environment,OptionName=EnvironmentType,Value=SingleInstance \
    Namespace=aws:elasticbeanstalk:application:environment,OptionName=DDB_TABLE_NAME,Value=beanstalk-lab-visits \
    Namespace=aws:elasticbeanstalk:application:environment,OptionName=AWS_REGION,Value=eu-west-1

# GitHub OIDC deploy role (see .github/workflows/deploy.yml for the trust
# condition and the scoped inline policy)
```

## Local run

```bash
mvn -DskipTests package
java -jar target/beanstalk-app.jar        # binds to PORT env var, default 5000
curl http://localhost:5000/
```

Without `DDB_TABLE_NAME` set, `/` still responds successfully with
`"dynamoDbConnected": false` — DynamoDB is a real dependency, not a hard
requirement to boot.

## Demonstrating a new release

```bash
# make a change, then:
git commit -am "change"
git push origin main
# GitHub Actions builds, uploads to S3, creates a new EB application version,
# deploys it, and blocks on environment-updated + a health/version assertion
```

Version history is visible any time via:

```bash
aws elasticbeanstalk describe-application-versions --application-name beanstalk-lab
```
