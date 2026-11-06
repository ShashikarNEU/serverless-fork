# Serverless: Email Verification Cloud Function (GCP)

A Java 17 **Google Cloud Function (2nd gen)** triggered by **Pub/Sub**. When a user signs up through the [webapp](https://github.com/ShashikarNEU/webapp) API, this function sends them a verification email via Mailgun and records when the verification link expires.

Infrastructure (topic, function, VPC connector, secrets) is provisioned by [tf-gcp-infra](https://github.com/ShashikarNEU/tf-gcp-infra).

---

## How It Works

```
POST /v1/user (webapp)
        |
        v
Spring Boot publishes "<uuid>:<email>" to Pub/Sub topic `verify-email`
        |
        v
Eventarc triggers this Cloud Function (retries on failure)
        |
        +--> Mailgun API: send verification link
        |
        +--> Cloud SQL (private IP, via Serverless VPC Access connector):
             set users.email_expiry_time = NOW() + 2 minutes
        |
        v
User clicks link -> GET /v1/user/verify?token=<uuid>:<email> -> account verified
```

- **Retries:** if Mailgun returns an error, the function throws so Pub/Sub redelivers the event. A malformed message is logged and dropped, since retrying it can never succeed.
- **Connection pooling:** the Hikari pool is created once per function instance and reused across warm invocations, instead of per message.
- **No secrets in code:** the DB password and Mailgun API key are injected as environment variables from **Secret Manager** by Terraform.

---

## Tech Stack

| Tool | Purpose |
|------|---------|
| Java 17, Maven | Function runtime and build |
| Google Cloud Functions (2nd gen) | Serverless compute (`CloudEventsFunction`) |
| Pub/Sub + Eventarc | Event trigger |
| Cloud SQL (MySQL), HikariCP | Store the verification link expiry |
| Serverless VPC Access connector | Reach Cloud SQL over its private IP |
| Secret Manager | DB password and Mailgun API key |
| Mailgun | Email delivery |

---

## Configuration

All configuration comes from environment variables (set by Terraform in `tf-gcp-infra`):

| Variable | Description | Source |
|----------|-------------|--------|
| `DB_IP_ADDRESS` | Cloud SQL private IP | Terraform output |
| `DB_NAME` | Database name | Terraform variable |
| `DB_USER` | Database user | Terraform variable |
| `DB_PASSWORD` | Database password | Secret Manager |
| `MAILGUN_API_KEY` | Mailgun sending key | Secret Manager |
| `MAILGUN_DOMAIN` | Mailgun sending domain | Terraform variable |
| `VERIFY_BASE_URL` | e.g. `https://your-domain/v1/user/verify` | Terraform variable |

---

## Build and Deploy

Terraform deploys the function from a zipped source archive in a Cloud Storage bucket. To build that archive:

```bash
cd function-source
mvn -B package          # compile check
zip -r ../function-source.zip pom.xml src
```

Upload the zip by pointing the `bucket_source` variable in `tf-gcp-infra` at it, then `terraform apply`.

Run locally with the Functions Framework:

```bash
cd function-source
mvn function:run
```

---

## Project Structure

```
serverless-fork/
├── function-source/
│   ├── pom.xml
│   └── src/main/java/gcfv2pubsub/PubSubFunction.java   # Function entry point
└── README.md
```

---

## Related Repositories

- [webapp](https://github.com/ShashikarNEU/webapp): Spring Boot API that publishes the Pub/Sub event
- [tf-gcp-infra](https://github.com/ShashikarNEU/tf-gcp-infra): Terraform for the topic, function, VPC connector and secrets
