package citypass.loginfederado.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.List;

public record AnomalyScoreResponse(
        @JsonProperty("risk_score")
        double riskScore,

        String decision,   // "ALLOW" | "REVIEW" | "BLOCK"
        List<String> reasons
) {}