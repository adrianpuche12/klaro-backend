package balance.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.autoconfigure.jackson.JacksonAutoConfiguration;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import java.time.LocalDateTime;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/** El ObjectMapper que arma Spring Boot (el que usan los controllers) publica las horas en UTC con "Z". */
class UtcDateTimeBootMapperTest {

    @Test
    void bootObjectMapper_writesUtcInstant() {
        new ApplicationContextRunner()
                .withConfiguration(AutoConfigurations.of(JacksonAutoConfiguration.class))
                .withUserConfiguration(UtcDateTimeConfig.class)
                .run(ctx -> {
                    String json = ctx.getBean(ObjectMapper.class)
                            .writeValueAsString(Map.of("at", LocalDateTime.of(2026, 9, 8, 19, 43)));
                    System.out.println("JSON=" + json);
                    assertThat(json).contains("2026-09-08T19:43:00Z");
                });
    }
}
