package com.vatsasarthak.notifyr.config;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.env.EnvironmentPostProcessor;
import org.springframework.core.env.ConfigurableEnvironment;

import java.util.Map;
import java.util.TimeZone;

// The PostgreSQL JDBC driver sends the JVM's default time zone ID when it connects. On Windows the JVM
// reports legacy IDs such as "Asia/Calcutta", which current PostgreSQL images no longer accept
// ("FATAL: invalid value for parameter TimeZone"). Switch such aliases to their canonical IDs before
// any connection is opened. Registered in META-INF/spring.factories.
public class TimeZoneNormalizer implements EnvironmentPostProcessor {

    static final Map<String, String> LEGACY_ZONE_IDS = Map.of(
            "Asia/Calcutta", "Asia/Kolkata",
            "Asia/Katmandu", "Asia/Kathmandu",
            "Asia/Rangoon", "Asia/Yangon",
            "Asia/Saigon", "Asia/Ho_Chi_Minh",
            "Asia/Dacca", "Asia/Dhaka",
            "Europe/Kiev", "Europe/Kyiv",
            "America/Godthab", "America/Nuuk",
            "Atlantic/Faeroe", "Atlantic/Faroe"
    );

    @Override
    public void postProcessEnvironment(ConfigurableEnvironment environment, SpringApplication application) {
        String canonical = LEGACY_ZONE_IDS.get(TimeZone.getDefault().getID());
        if (canonical != null) {
            TimeZone.setDefault(TimeZone.getTimeZone(canonical));
        }
    }
}
