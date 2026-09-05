package citypass.loginfederado.metrics;

public record HourlyLoginBucket(int hour, long count) {
}