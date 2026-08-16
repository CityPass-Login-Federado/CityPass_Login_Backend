package ar.edu.uade.citypass.loginfederado.service;

import java.util.List;

public record RefreshTokenPrincipal(String username, List<String> roles) {
}