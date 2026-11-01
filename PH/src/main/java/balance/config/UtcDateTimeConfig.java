package balance.config;

import com.fasterxml.jackson.core.JsonGenerator;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.DeserializationContext;
import com.fasterxml.jackson.databind.JsonDeserializer;
import com.fasterxml.jackson.databind.JsonSerializer;
import com.fasterxml.jackson.databind.SerializerProvider;
import org.springframework.boot.autoconfigure.jackson.Jackson2ObjectMapperBuilderCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.io.IOException;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;

/**
 * Fechas y horas técnicas (createdAt, openedAt, closedAt…) como instantes UTC.
 *
 * Todos los LocalDateTime del sistema son marcas que pone el servidor con LocalDateTime.now(),
 * y el servidor corre en UTC (BalanceApplication fija la zona por defecto). Antes se
 * serializaban SIN zona ("2026-09-08T19:43:00") y el navegador las interpretaba como hora
 * local: una empresa de Honduras (UTC-6) veía todas las horas 6 h adelantadas.
 *
 * Ahora salen con "Z" ("2026-09-08T19:43:00Z") y el frontend las muestra en la zona horaria
 * de la empresa. Al leer, se acepta con o sin zona: con zona se convierte a UTC; sin zona se
 * asume que ya es UTC (compatibilidad con clientes viejos que reenvían el objeto completo).
 *
 * Las fechas "solo día" (LocalDate: fecha de un cierre, de un pago) no se tocan: son fechas
 * de negocio y ya se calculan en la zona de la empresa (TenantConfigService.today()).
 */
@Configuration
public class UtcDateTimeConfig {

    static final DateTimeFormatter UTC_OUT = DateTimeFormatter.ISO_OFFSET_DATE_TIME;

    @Bean
    public Jackson2ObjectMapperBuilderCustomizer utcLocalDateTime() {
        return builder -> builder
                .serializerByType(LocalDateTime.class, new UtcLocalDateTimeSerializer())
                .deserializerByType(LocalDateTime.class, new UtcLocalDateTimeDeserializer());
    }

    static class UtcLocalDateTimeSerializer extends JsonSerializer<LocalDateTime> {
        @Override
        public void serialize(LocalDateTime value, JsonGenerator gen, SerializerProvider serializers) throws IOException {
            gen.writeString(value.atOffset(ZoneOffset.UTC).format(UTC_OUT));
        }
    }

    static class UtcLocalDateTimeDeserializer extends JsonDeserializer<LocalDateTime> {
        @Override
        public LocalDateTime deserialize(JsonParser p, DeserializationContext ctxt) throws IOException {
            String text = p.getValueAsString();
            if (text == null || text.isBlank()) {
                return null;
            }
            String s = text.trim();
            try {
                return OffsetDateTime.parse(s).withOffsetSameInstant(ZoneOffset.UTC).toLocalDateTime();
            } catch (DateTimeParseException withoutZone) {
                return LocalDateTime.parse(s);
            }
        }
    }
}
