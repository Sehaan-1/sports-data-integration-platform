package com.sdip.connectors;

import com.sdip.connectors.VendorDtos.OAuthTokenResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.reactive.function.client.WebClientException;
import org.springframework.web.util.UriBuilder;

import java.net.URI;
import java.time.Duration;
import java.time.Instant;
import java.util.function.Function;

/**
 * Thin authenticated HTTP client over WebClient (ADR-04). Blocking calls are
 * deliberate: they run under JDK 21 virtual threads in the application, which
 * keeps connector code simple while remaining cheap.
 */
public class VendorHttpClient {

    private static final Logger log = LoggerFactory.getLogger(VendorHttpClient.class);
    private static final Duration CALL_TIMEOUT = Duration.ofSeconds(20);

    private final ConnectorProperties.Vendor props;
    private final WebClient webClient;
    private volatile String cachedToken;
    private volatile Instant tokenExpiry = Instant.EPOCH;

    public VendorHttpClient(ConnectorProperties.Vendor props) {
        this.props = props;
        this.webClient = WebClient.builder().baseUrl(props.getBaseUrl()).build();
    }

    /** GET a JSON resource into {@code type}, mapping failures to {@link VendorApiException}. */
    public <T> T get(String path, Function<UriBuilder, URI> uriCustomizer, Class<T> type) {
        try {
            T body = webClient.get()
                    .uri(uriBuilder -> uriCustomizer.apply(uriBuilder.path(path)))
                    .headers(h -> authorize(h::setBearerAuth))
                    .exchangeToMono(response -> {
                        HttpStatusCode status = response.statusCode();
                        if (status.is2xxSuccessful()) {
                            return response.bodyToMono(type);
                        }
                        return response.bodyToMono(String.class).defaultIfEmpty("")
                                .map(b -> {
                                    boolean retryable = status.value() == 429 || status.is5xxServerError();
                                    return new VendorApiException(
                                            "HTTP " + status.value() + " from " + props.getBaseUrl() + path
                                                    + ": " + abbreviate(b),
                                            retryable, status.value());
                                })
                                .flatMap(err -> reactor.core.publisher.Mono.error(err));
                    })
                    .block(CALL_TIMEOUT);
            if (body == null) {
                throw new VendorApiException("empty body from " + path, false, -1);
            }
            return body;
        } catch (VendorApiException e) {
            throw e;
        } catch (WebClientException e) {
            String msg = e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName();
            boolean malformed = msg.contains("Decoding") || msg.contains("JSON");
            throw new VendorApiException("vendor call failed: " + abbreviate(msg), e, !malformed);
        } catch (Exception e) {
            throw new VendorApiException("vendor call failed: " + abbreviate(String.valueOf(e.getMessage())), e, true);
        }
    }

    /** Convenience GET with no extra query params. */
    public <T> T get(String path, Class<T> type) {
        return get(path, UriBuilder::build, type);
    }

    private void authorize(java.util.function.Consumer<String> bearer) {
        switch (props.getAuthType()) {
            case TOKEN -> {
                if (props.getToken() != null && !props.getToken().isBlank()) {
                    bearer.accept(props.getToken());
                }
            }
            case OAUTH2 -> bearer.accept(oauthAccessToken());
            case NONE -> {
            }
        }
    }

    /** Client-credentials grant with in-memory token caching. */
    private synchronized String oauthAccessToken() {
        if (cachedToken != null && Instant.now().isBefore(tokenExpiry.minusSeconds(30))) {
            return cachedToken;
        }
        try {
            OAuthTokenResponse response = webClient.post()
                    .uri(props.getTokenUri())
                    .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                    .bodyValue("grant_type=client_credentials&client_id=" + props.getClientId()
                            + "&client_secret=" + props.getClientSecret())
                    .retrieve()
                    .bodyToMono(OAuthTokenResponse.class)
                    .block(CALL_TIMEOUT);
            if (response == null || response.accessToken() == null) {
                throw new VendorApiException("vendor token endpoint returned no access_token", false, -1);
            }
            cachedToken = response.accessToken();
            tokenExpiry = Instant.now().plusSeconds(Math.max(60, response.expiresIn()));
            return cachedToken;
        } catch (VendorApiException e) {
            throw e;
        } catch (Exception e) {
            throw new VendorApiException("OAuth2 token request failed: " + e.getMessage(), e, true);
        }
    }

    private static String abbreviate(String s) {
        if (s == null) {
            return "";
        }
        return s.length() > 300 ? s.substring(0, 300) + "…" : s;
    }

    /** Generic typed reference helper for future endpoints. */
    public static <T> ParameterizedTypeReference<T> typeRef() {
        return new ParameterizedTypeReference<>() {
        };
    }
}
