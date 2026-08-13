package com.gpr.payroll.client;

import java.util.Map;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

/**
 * Reads a company's resolved template from wos-notification, which owns them.
 *
 * <p>Returns null on any failure rather than propagating: a payslip PDF must still be downloadable
 * when the template service is unreachable, so the renderer falls back to a built-in layout. Losing
 * the company's styling is a far better outcome than an employee being unable to get their payslip.
 */
@Slf4j
@Component
public class TemplateClient {

    private final RestClient restClient;
    private final String internalToken;

    public TemplateClient(
            @Value("${wos-notification.base-url:http://localhost:8086/api/notification}") String baseUrl,
            @Value("${internal.service-token:wos-internal-dev-token}") String internalToken) {
        this.restClient = RestClient.builder().baseUrl(baseUrl).build();
        this.internalToken = internalToken;
    }

    /**
     * The layout to render with: the company's override when it has one, else the system default.
     *
     * @return the {@code EmailLayout} map (blocks + document styling), or null if unavailable
     */
    @SuppressWarnings("unchecked")
    public Map<String, Object> resolveLayout(String templateKey, Long companyId) {
        if (companyId == null) return null;
        try {
            Map<String, Object> body = restClient.get()
                    .uri(uri -> uri.path("/internal/templates/{key}")
                            .queryParam("companyId", companyId)
                            .build(templateKey))
                    .header("X-Internal-Token", internalToken)
                    .retrieve()
                    .body(Map.class);
            if (body == null) return null;

            Map<String, Object> config = (Map<String, Object>) body.get("config");
            Map<String, Object> template = (Map<String, Object>) body.get("template");
            // Same precedence the editor uses: a company override wins, otherwise the shipped
            // default. Resolving it here keeps that rule in one place per consumer.
            Object layout = config == null ? null : config.get("layout");
            if (layout == null && template != null) layout = template.get("defaultLayout");
            return (Map<String, Object>) layout;
        } catch (Exception e) {
            log.warn("Could not resolve template {} for company {} — falling back to the built-in "
                    + "layout: {}", templateKey, companyId, e.getMessage());
            return null;
        }
    }
}
