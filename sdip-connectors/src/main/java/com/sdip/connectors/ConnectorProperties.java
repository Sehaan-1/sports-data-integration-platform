package com.sdip.connectors;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.NestedConfigurationProperty;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Externalized connector configuration (§4.1). Example:
 * <pre>
 * sdip.connectors.vendors.catapult.enabled=true
 * sdip.connectors.vendors.catapult.base-url=http://localhost:9091
 * sdip.connectors.vendors.catapult.auth-type=TOKEN
 * sdip.connectors.vendors.catapult.token=...
 * sdip.connectors.vendors.catapult.squad-id=&lt;uuid&gt;
 * </pre>
 * Live secrets should be stored in the encrypted {@code vendor_credential}
 * table (AES-GCM-256); plaintext props exist for local development only.
 */
@ConfigurationProperties(prefix = "sdip.connectors")
public class ConnectorProperties {

    private Map<String, Vendor> vendors = new HashMap<>();

    /** Global poll interval for scheduled incremental sync (ISO-8601 duration, e.g. PT15M). */
    private String pollInterval = "PT15M";

    public Map<String, Vendor> getVendors() {
        return vendors;
    }

    public void setVendors(Map<String, Vendor> vendors) {
        this.vendors = vendors;
    }

    public String getPollInterval() {
        return pollInterval;
    }

    public void setPollInterval(String pollInterval) {
        this.pollInterval = pollInterval;
    }

    public Vendor require(String key) {
        Vendor v = vendors.get(key);
        if (v == null) {
            throw new IllegalArgumentException("connector '" + key + "' is not configured");
        }
        return v;
    }

    public enum AuthType {TOKEN, OAUTH2, NONE}

    public static class Vendor {
        private boolean enabled;
        private String baseUrl;
        private AuthType authType = AuthType.TOKEN;
        private String token;
        private String clientId;
        private String clientSecret;
        private String tokenUri;
        private UUID squadId;
        /** Overlap window (hours) re-fetched on each sync to capture late-arriving vendor edits (C4). */
        private int overlapHours = 48;
        /** First-sync lookback when no watermark exists (hours). */
        private int initialLookbackHours = 24 * 30;
        private int pageSize = 200;
        /** Client-side rate limit: max requests per second to this vendor. */
        private double requestsPerSecond = 5.0;

        public boolean isEnabled() {
            return enabled;
        }

        public void setEnabled(boolean enabled) {
            this.enabled = enabled;
        }

        public String getBaseUrl() {
            return baseUrl;
        }

        public void setBaseUrl(String baseUrl) {
            this.baseUrl = baseUrl;
        }

        public AuthType getAuthType() {
            return authType;
        }

        public void setAuthType(AuthType authType) {
            this.authType = authType;
        }

        public String getToken() {
            return token;
        }

        public void setToken(String token) {
            this.token = token;
        }

        public String getClientId() {
            return clientId;
        }

        public void setClientId(String clientId) {
            this.clientId = clientId;
        }

        public String getClientSecret() {
            return clientSecret;
        }

        public void setClientSecret(String clientSecret) {
            this.clientSecret = clientSecret;
        }

        public String getTokenUri() {
            return tokenUri;
        }

        public void setTokenUri(String tokenUri) {
            this.tokenUri = tokenUri;
        }

        public UUID getSquadId() {
            return squadId;
        }

        public void setSquadId(UUID squadId) {
            this.squadId = squadId;
        }

        public int getOverlapHours() {
            return overlapHours;
        }

        public void setOverlapHours(int overlapHours) {
            this.overlapHours = overlapHours;
        }

        public int getInitialLookbackHours() {
            return initialLookbackHours;
        }

        public void setInitialLookbackHours(int initialLookbackHours) {
            this.initialLookbackHours = initialLookbackHours;
        }

        public int getPageSize() {
            return pageSize;
        }

        public void setPageSize(int pageSize) {
            this.pageSize = pageSize;
        }

        public double getRequestsPerSecond() {
            return requestsPerSecond;
        }

        public void setRequestsPerSecond(double requestsPerSecond) {
            this.requestsPerSecond = requestsPerSecond;
        }
    }
}
