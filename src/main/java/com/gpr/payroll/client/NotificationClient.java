package com.gpr.payroll.client;

import java.util.Map;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

/**
 * Raises in-app notifications through wos-notification's internal endpoint.
 *
 * <p>Every call is best-effort: a notification that fails to send must never roll back or block the
 * payroll action that triggered it. Releasing a run is the money event; telling people about it is
 * secondary, and a failure there is logged rather than surfaced.
 */
@Slf4j
@Component
public class NotificationClient {

    private final RestClient restClient;
    private final String internalToken;

    public NotificationClient(
            @Value("${wos-notification.base-url:http://localhost:8086/api/notification}") String baseUrl,
            @Value("${internal.service-token:wos-internal-dev-token}") String internalToken) {
        this.restClient = RestClient.builder().baseUrl(baseUrl).build();
        this.internalToken = internalToken;
    }

    public void notify(Long companyId, Long userId, String type, String title, String body,
                       String linkUrl) {
        if (companyId == null || userId == null) return;
        try {
            restClient.post()
                    .uri("/internal/notifications")
                    .header("X-Internal-Token", internalToken)
                    .body(Map.of(
                            "companyId", companyId,
                            "userId", userId,
                            "type", type,
                            "title", title,
                            "body", body == null ? "" : body,
                            "linkUrl", linkUrl == null ? "" : linkUrl))
                    .retrieve()
                    .toBodilessEntity();
        } catch (Exception e) {
            log.warn("Notification {} for user {} could not be sent: {}", type, userId, e.getMessage());
        }
    }
}
