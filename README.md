# Serverless — GCP Cloud Functions

Node.js **Google Cloud Functions** triggered by **Pub/Sub** events, designed to handle asynchronous background tasks for the [webapp](https://github.com/ShashikarNEU/webapp) backend. Primary use case: sending email verification links to newly registered users.

---

## Tech Stack

| Tool | Purpose |
|------|---------|
| Node.js | Cloud Function runtime |
| Google Cloud Functions | Serverless compute |
| Google Cloud Pub/Sub | Event trigger mechanism |
| SendGrid / Mailgun | Email delivery |
| Google Cloud Storage | Storing function artifacts |
| Terraform | Infrastructure provisioned via [tf-gcp-infra](https://github.com/ShashikarNEU/tf-gcp-infra) |

---

## How It Works

```
User Registers (webapp)
        ↓
Spring Boot publishes message to Pub/Sub topic
        ↓
Cloud Function triggered automatically
        ↓
Function sends verification email to the user
        ↓
User clicks link → account verified
```

1. When a new user registers via the REST API, the Spring Boot app publishes a message to a **GCP Pub/Sub topic** containing the user's email and a verification token.
2. This Cloud Function is subscribed to that Pub/Sub topic and triggers automatically upon receiving a message.
3. The function parses the message payload, constructs a verification email, and sends it via an email provider (SendGrid/Mailgun).
4. The verification link directs the user back to the API to confirm their email address.

---

## Prerequisites

- Node.js 18+
- Google Cloud SDK (`gcloud` CLI)
- A GCP project with Cloud Functions and Pub/Sub APIs enabled
- An email provider API key (SendGrid or Mailgun)

---

## Setup & Deployment

### 1. Clone the Repository

```bash
git clone https://github.com/ShashikarNEU/serverless-fork.git
cd serverless-fork
```

### 2. Install Dependencies

```bash
npm install
```

### 3. Configure Environment Variables

Set the following environment variables when deploying (or via a `.env` file for local testing):

```env
EMAIL_API_KEY=your_sendgrid_or_mailgun_api_key
VERIFICATION_BASE_URL=https://your-api-domain.com/v1/user/verify
```

### 4. Deploy to GCP

```bash
gcloud functions deploy verifyEmail \
  --runtime nodejs18 \
  --trigger-topic user-verification \
  --region us-east1 \
  --entry-point verifyEmail \
  --set-env-vars EMAIL_API_KEY=your_key,VERIFICATION_BASE_URL=your_url
```

---

## Project Structure

```
serverless-fork/
├── index.js          # Cloud Function entry point
├── package.json      # Node.js dependencies
└── README.md
```

---

## Local Testing

You can test the function locally using the Functions Framework:

```bash
npm install -g @google-cloud/functions-framework
npx functions-framework --target=verifyEmail
```

Then send a test Pub/Sub event:

```bash
curl -X POST http://localhost:8080 \
  -H "Content-Type: application/json" \
  -d '{
    "message": {
      "data": "BASE64_ENCODED_PAYLOAD"
    }
  }'
```

---

## Related Repositories

- [webapp](https://github.com/ShashikarNEU/webapp) — Spring Boot API that publishes Pub/Sub events
- [tf-gcp-infra](https://github.com/ShashikarNEU/tf-gcp-infra) — Terraform infrastructure including Pub/Sub topics and Cloud Function configuration
