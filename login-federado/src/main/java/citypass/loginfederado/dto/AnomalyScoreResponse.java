package citypass.loginfederado.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.List;
import java.util.Map;

public record AnomalyScoreResponse(

        @JsonProperty("risk_score")
        double riskScore,

        String decision,

        List<String> reasons,

        @JsonProperty("proposed_decision")
        String proposedDecision,

        @JsonProperty("model_version")
        String modelVersion,

        @JsonProperty("model_sha256")
        String modelSha256,

        @JsonProperty("policy_sha256")
        String policySha256,

        @JsonProperty("feature_semantics")
        String featureSemantics,

        String mode,

        Map<String, Integer> features

) {

    public AnomalyScoreResponse(
            double riskScore,
            String decision,
            List<String> reasons
    ) {
        this(
                riskScore,
                decision,
                reasons,
                null,
                null,
                null,
                null,
                null,
                null,
                null
        );
    }
}