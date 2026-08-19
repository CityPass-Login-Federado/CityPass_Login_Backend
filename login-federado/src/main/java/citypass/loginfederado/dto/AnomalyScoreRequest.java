package citypass.loginfederado.dto;

import com.fasterxml.jackson.annotation.JsonProperty;

public record AnomalyScoreRequest(
        String username,
        String ip,
        @JsonProperty("user_agent") 
        String userAgent,
        String timestamp,
        boolean success
) {}