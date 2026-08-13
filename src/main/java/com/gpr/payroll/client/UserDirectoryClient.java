package com.gpr.payroll.client;

import com.gpr.kernel.dto.UserSummaryDto;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

/**
 * Resolves canonical identity display data (name/email) from gpr-auth by id. Personal data is not
 * stored in WorkOS, so payslip generation resolves the employee name here.
 */
@Slf4j
@Component
public class UserDirectoryClient {

    private final RestClient restClient;

    public UserDirectoryClient(
            @Value("${gpr-auth.base-url:http://localhost:8081/api/auth}") String baseUrl) {
        this.restClient = RestClient.builder().baseUrl(baseUrl).build();
    }

    /**
     * Resolves summaries for many ids in one call, keyed by id — used when generating a payroll run so
     * employee names are fetched once for the whole company instead of per payslip. Degrades to an empty
     * map on failure so a directory outage never aborts a run (names fall back to the employee code).
     *
     * @param ids identity ids to resolve (null/empty → empty map)
     * @return summaries keyed by identity id
     */
    public Map<Long, UserSummaryDto> getSummaries(Collection<Long> ids) {
        if (ids == null || ids.isEmpty()) {
            return Map.of();
        }
        Long[] idArray = ids.stream().distinct().toArray(Long[]::new);
        try {
            List<UserSummaryDto> summaries = restClient.get()
                    .uri(uriBuilder -> uriBuilder.path("/users/summaries")
                            .queryParam("ids", (Object[]) idArray)
                            .build())
                    .retrieve()
                    .body(new ParameterizedTypeReference<List<UserSummaryDto>>() {});
            return summaries == null ? Map.of()
                    : summaries.stream().collect(Collectors.toMap(UserSummaryDto::getId, Function.identity()));
        } catch (Exception e) {
            log.warn("Batch user summary lookup failed for {} ids: {}", idArray.length, e.getMessage());
            return Map.of();
        }
    }

    /**
     * Resolves the identity behind a signed-in principal. The JWT carries only the email, so
     * self-service endpoints ("my payslips") need this to reach the employee record.
     */
    public UserSummaryDto getByEmail(String email) {
        if (email == null || email.isBlank()) {
            return null;
        }
        try {
            return restClient.get()
                    .uri(uriBuilder ->
                            uriBuilder.path("/users/by-email").queryParam("email", email).build())
                    .retrieve()
                    .body(UserSummaryDto.class);
        } catch (Exception e) {
            log.warn("User lookup by email failed: {}", e.getMessage());
            return null;
        }
    }

    /**
     * Company header details for a rendered document (payslip PDF), keyed by the template variable
     * names the layout uses: {@code companyName}, {@code companyAddress}, {@code companyEmail},
     * {@code companyPhone}.
     *
     * <p>Returns whatever resolves and blanks the rest — an incomplete header is fine, because the
     * renderer skips lines that fill to nothing. A failure here must never stop a payslip being
     * downloaded, so it degrades to an empty map rather than throwing.
     */
    public Map<String, String> getCompanyDetails(Long companyId) {
        if (companyId == null) return Map.of();
        try {
            Map<?, ?> profile = restClient.get()
                    .uri("/companies/{id}/profile", companyId)
                    .retrieve()
                    .body(Map.class);
            if (profile == null) return Map.of();

            Map<String, String> out = new java.util.LinkedHashMap<>();
            // The name lives on the company record, not the profile (which holds only the editable
            // "My Company" fields), so it takes a second call.
            out.put("companyName", companyName(companyId));
            // `address` is the postal one; `headquarters` is the city-level fallback.
            String address = str(profile.get("address"));
            out.put("companyAddress", address.isBlank() ? str(profile.get("headquarters")) : address);
            out.put("companyEmail", str(profile.get("email")));
            out.put("companyPhone", str(profile.get("phone")));
            out.put("companyWebsite", str(profile.get("website")));
            return out;
        } catch (Exception e) {
            log.warn("Company profile lookup failed for {}: {}", companyId, e.getMessage());
            return Map.of();
        }
    }

    /** Blank rather than throwing — a payslip without a company name still beats no payslip. */
    private String companyName(Long companyId) {
        try {
            Map<?, ?> company = restClient.get()
                    .uri("/companies/{id}", companyId)
                    .retrieve()
                    .body(Map.class);
            return company == null ? "" : str(company.get("name"));
        } catch (Exception e) {
            log.warn("Company name lookup failed for {}: {}", companyId, e.getMessage());
            return "";
        }
    }

    private static String str(Object v) {
        return v == null ? "" : String.valueOf(v);
    }

    /** Resolves a single user summary by identity id, or null on miss / lookup failure. */
    public UserSummaryDto getSummary(Long id) {
        if (id == null) {
            return null;
        }
        try {
            List<UserSummaryDto> summaries = restClient.get()
                    .uri(uriBuilder -> uriBuilder.path("/users/summaries")
                            .queryParam("ids", id)
                            .build())
                    .retrieve()
                    .body(new ParameterizedTypeReference<List<UserSummaryDto>>() {});
            return summaries == null || summaries.isEmpty() ? null : summaries.get(0);
        } catch (Exception e) {
            log.warn("User summary lookup failed for id {}: {}", id, e.getMessage());
            return null;
        }
    }
}
