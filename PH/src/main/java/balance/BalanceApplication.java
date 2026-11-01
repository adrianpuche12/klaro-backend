package balance;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

@SpringBootApplication
public class BalanceApplication {

	public static void main(String[] args) {
		// Las marcas técnicas (createdAt, openedAt…) se guardan con LocalDateTime.now() y se
		// publican como UTC (ver UtcDateTimeConfig): el servidor tiene que correr SIEMPRE en
		// UTC, sin depender de la zona del host o del contenedor.
		java.util.TimeZone.setDefault(java.util.TimeZone.getTimeZone("UTC"));
		SpringApplication.run(BalanceApplication.class, args);
	}

}