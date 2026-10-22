
package citypass.loginfederado.event;

public enum EdaEventType {

    LOGIN("identidad.login", "LoginExitoso"),
    REFRESH("identidad.refresh", "TokenRenovado"),
    LOGOUT("identidad.logout", "SesionCerrada");

    private static final String NAMESPACE = "com.citypass.auth";

    private final String internalType;
    private final String edaName;

    EdaEventType(String internalType, String edaName) {
        this.internalType = internalType;
        this.edaName = edaName;
    }

    public String internalType() {
        return internalType;
    }

    public String edaName() {
        return edaName;
    }

    public String fqn() {
        return NAMESPACE + "." + edaName;
    }

    public static EdaEventType fromInternalType(String internalType) {
        for (EdaEventType type : values()) {
            if (type.internalType.equals(internalType)) {
                return type;
            }
        }

        throw new IllegalArgumentException(
                "Unsupported EDA event type: " + internalType
        );
    }
}
