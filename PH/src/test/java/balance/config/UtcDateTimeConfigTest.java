package balance.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.http.converter.json.Jackson2ObjectMapperBuilder;

import java.time.LocalDateTime;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/** Las marcas técnicas viajan como instantes UTC ("…Z") y se leen con o sin zona. */
class UtcDateTimeConfigTest {

    private final ObjectMapper mapper;

    UtcDateTimeConfigTest() {
        Jackson2ObjectMapperBuilder builder = new Jackson2ObjectMapperBuilder();
        new UtcDateTimeConfig().utcLocalDateTime().customize(builder);
        mapper = builder.build();
    }

    record Holder(LocalDateTime at) {}

    @Test
    void serializesAsUtcInstant() throws Exception {
        String json = mapper.writeValueAsString(Map.of("at", LocalDateTime.of(2026, 9, 8, 19, 43)));
        assertThat(json).contains("\"2026-09-08T19:43:00Z\"");
    }

    @Test
    void readsUtcInstant() throws Exception {
        Holder h = mapper.readValue("{\"at\":\"2026-09-08T19:43:00Z\"}", Holder.class);
        assertThat(h.at()).isEqualTo(LocalDateTime.of(2026, 9, 8, 19, 43));
    }

    @Test
    void readsOffset_convertingToUtc() throws Exception {
        // 13:43 en Honduras (UTC-6) = 19:43 UTC
        Holder h = mapper.readValue("{\"at\":\"2026-09-08T13:43:00-06:00\"}", Holder.class);
        assertThat(h.at()).isEqualTo(LocalDateTime.of(2026, 9, 8, 19, 43));
    }

    @Test
    void readsWithoutZone_asUtc_forOldClients() throws Exception {
        Holder h = mapper.readValue("{\"at\":\"2026-09-08T19:43:00\"}", Holder.class);
        assertThat(h.at()).isEqualTo(LocalDateTime.of(2026, 9, 8, 19, 43));
    }
}
