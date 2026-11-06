package gcfv2pubsub;

import com.google.cloud.functions.CloudEventsFunction;
import com.google.events.cloud.pubsub.v1.Message;
import com.google.events.cloud.pubsub.v1.MessagePublishedData;
import com.google.gson.Gson;
import com.mashape.unirest.http.HttpResponse;
import com.mashape.unirest.http.JsonNode;
import com.mashape.unirest.http.Unirest;
import com.mashape.unirest.http.exceptions.UnirestException;
import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import io.cloudevents.CloudEvent;

import javax.sql.DataSource;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.util.Base64;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Triggered by the verify-email Pub/Sub topic. Sends a verification link via Mailgun
 * and records when the link expires.
 *
 * All configuration comes from environment variables. Secrets (DB password, Mailgun key)
 * are injected by Terraform from Secret Manager, never hardcoded.
 */
public class PubSubFunction implements CloudEventsFunction {
  private static final Logger logger = Logger.getLogger(PubSubFunction.class.getName());
  private static final Gson GSON = new Gson();

  private static final String DB_IP_ADDRESS = System.getenv("DB_IP_ADDRESS");
  private static final String DB_NAME = System.getenv("DB_NAME");
  private static final String DB_USER = System.getenv("DB_USER");
  private static final String DB_PASSWORD = System.getenv("DB_PASSWORD");

  private static final String MAILGUN_API_KEY = System.getenv("MAILGUN_API_KEY");
  private static final String MAILGUN_DOMAIN = System.getenv("MAILGUN_DOMAIN");
  // e.g. https://csye6225-cloud-project.me/v1/user/verify
  private static final String VERIFY_BASE_URL = System.getenv("VERIFY_BASE_URL");

  // Created once per function instance and reused across warm invocations,
  // instead of opening a new pool on every message.
  private static volatile DataSource dataSource;

  @Override
  public void accept(CloudEvent event) {
    String cloudEventData = new String(event.getData().toBytes(), StandardCharsets.UTF_8);
    MessagePublishedData data = GSON.fromJson(cloudEventData, MessagePublishedData.class);
    Message message = data.getMessage();
    String decodedData = new String(Base64.getDecoder().decode(message.getData()), StandardCharsets.UTF_8);

    // Payload format published by the webapp: "<uuid>:<email>"
    String[] parts = decodedData.split(":", 2);
    if (parts.length != 2) {
      logger.severe("Malformed message, expected uuid:email");
      return; // ack and drop: retrying a malformed message will never succeed
    }
    String uuid = parts[0];
    String email = parts[1];
    logger.info("Processing verification for user: " + email);

    String verificationLink = VERIFY_BASE_URL + "?token=" + uuid + ":" + email;

    try {
      sendVerificationEmail(email, verificationLink);
      updateEmailSentTime(email);
    } catch (UnirestException e) {
      // Rethrow so Pub/Sub retries delivery (and eventually dead-letters it)
      throw new RuntimeException("Error sending verification email", e);
    }
  }

  private void updateEmailSentTime(String email) {
    String stmt = "UPDATE users SET email_expiry_time = DATE_ADD(NOW(), INTERVAL 2 MINUTE) WHERE username = ?";
    try (Connection conn = getDataSource().getConnection();
         PreparedStatement ps = conn.prepareStatement(stmt)) {
      ps.setString(1, email);
      ps.executeUpdate();
      logger.info("Updated email expiry for user: " + email);
    } catch (SQLException ex) {
      logger.log(Level.SEVERE, "Error while updating email expiry", ex);
    }
  }

  private static DataSource getDataSource() {
    if (dataSource == null) {
      synchronized (PubSubFunction.class) {
        if (dataSource == null) {
          HikariConfig config = new HikariConfig();
          config.setJdbcUrl("jdbc:mysql://" + DB_IP_ADDRESS + ":3306/" + DB_NAME);
          config.setUsername(DB_USER);
          config.setPassword(DB_PASSWORD);
          // One instance handles one request at a time, so a tiny pool is enough
          config.setMaximumPoolSize(2);
          dataSource = new HikariDataSource(config);
        }
      }
    }
    return dataSource;
  }

  private void sendVerificationEmail(String email, String verificationLink) throws UnirestException {
    if (MAILGUN_API_KEY == null || MAILGUN_API_KEY.isBlank()) {
      throw new IllegalStateException("MAILGUN_API_KEY is not set");
    }
    HttpResponse<JsonNode> response = Unirest.post("https://api.mailgun.net/v3/" + MAILGUN_DOMAIN + "/messages")
        .basicAuth("api", MAILGUN_API_KEY)
        .queryString("from", "Verification <noreply@" + MAILGUN_DOMAIN + ">")
        .queryString("to", email)
        .queryString("subject", "Verify your email")
        .queryString("text", "Click the following link to verify your email (valid for 2 minutes): " + verificationLink)
        .asJson();
    if (response.getStatus() >= 300) {
      throw new UnirestException("Mailgun returned HTTP " + response.getStatus());
    }
    logger.info("Verification email sent to: " + email);
  }
}
