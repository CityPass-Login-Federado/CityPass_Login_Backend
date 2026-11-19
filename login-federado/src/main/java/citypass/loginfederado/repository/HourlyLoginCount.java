package citypass.loginfederado.repository;

/** Proyección: cantidad de logins exitosos agrupados por hora del día (0-23). */
public interface HourlyLoginCount {
    Integer getHourOfDay();
    Long getLoginCount();
}