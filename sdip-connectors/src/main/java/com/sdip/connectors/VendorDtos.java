package com.sdip.connectors;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.List;

/**
 * Contract DTOs for the two vendor replicas (§4.1 contract-first development).
 * Shapes follow the public Catapult OpenField and VALD Hub documentation;
 * unknown fields are ignored to be forward-compatible.
 */
public final class VendorDtos {

    private VendorDtos() {
    }

    // ---- Catapult OpenField ----

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record CatapultAthletesResponse(List<CatapultAthlete> athletes) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record CatapultAthlete(String id,
                                  @JsonProperty("first_name") String firstName,
                                  @JsonProperty("last_name") String lastName,
                                  String email,
                                  @JsonProperty("date_of_birth") String dateOfBirth) {
        public String fullName() {
            return ((firstName != null ? firstName : "") + " " + (lastName != null ? lastName : "")).trim();
        }
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record CatapultActivitiesResponse(List<CatapultActivity> activities, PageInfo page) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record CatapultActivity(String id,
                                   @JsonProperty("athlete_id") String athleteId,
                                   @JsonProperty("start_time") String startTime,
                                   @JsonProperty("duration_minutes") Double durationMinutes,
                                   @JsonProperty("total_distance") Double totalDistance,
                                   @JsonProperty("high_speed_running") Double highSpeedRunning,
                                   @JsonProperty("player_load") Double playerLoad,
                                   @JsonProperty("max_velocity") Double maxVelocity) {
    }

    // ---- VALD Hub (ForceDecks) ----

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record ValdTestsResponse(List<ValdTest> tests, PageInfo page) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record ValdTest(@JsonProperty("test_id") String testId,
                           @JsonProperty("profile_id") String profileId,
                           @JsonProperty("athlete_name") String athleteName,
                           String email,
                           @JsonProperty("test_type") String testType,
                           @JsonProperty("recorded_utc") String recordedUtc,
                           @JsonProperty("peak_force_n") Double peakForceN,
                           @JsonProperty("asymmetry_pct") Double asymmetryPct,
                           @JsonProperty("rsi_modified") Double rsiModified,
                           @JsonProperty("peak_power_w") Double peakPowerW) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record PageInfo(int number, @JsonProperty("total_pages") int totalPages) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record OAuthTokenResponse(@JsonProperty("access_token") String accessToken,
                                     @JsonProperty("expires_in") long expiresIn) {
    }
}
