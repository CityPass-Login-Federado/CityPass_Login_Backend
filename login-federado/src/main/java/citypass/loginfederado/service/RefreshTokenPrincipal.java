package citypass.loginfederado.service;

import java.util.List;

public record RefreshTokenPrincipal(String username, String fullName, String email, List<String> roles) {
}