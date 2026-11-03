package balance.tenant.db;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.SmartInitializingSingleton;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Set;

/**
 * Guardia de arranque del aislamiento multi-tenant (RLS, migración V11).
 *
 * RLS solo protege si el backend se conecta con un rol sujeto a las políticas. Postgres
 * saltea RLS en silencio para el superusuario, para roles con BYPASSRLS y para el dueño
 * de las tablas (V11 no usa FORCE a propósito, para que el dueño migre). Un deploy con
 * {@code DB_USER} = dueño, o sin {@code DB_MIGRATION_USER} (Flyway y la app terminan con
 * el mismo usuario, que pasa a ser dueño), deja a todas las empresas sin la segunda
 * barrera y nadie se entera.
 *
 * Esta guardia lo verifica con la conexión real de la aplicación:
 * <ul>
 *   <li>en los perfiles {@code dev} y {@code prod} el backend NO arranca si falla alguna
 *       verificación (excepción con el detalle en el log);</li>
 *   <li>en cualquier otro perfil ({@code local}, {@code test}...) solo deja un warning,
 *       para no trabar el desarrollo con una base local de un solo usuario.</li>
 * </ul>
 *
 * Corre en {@link SmartInitializingSingleton}: después de Flyway y de JPA, pero antes de
 * que el servidor web empiece a escuchar, así un backend mal configurado nunca atiende
 * ni un request.
 */
@Component
public class DatabaseRoleGuard implements SmartInitializingSingleton {

    private static final Logger log = LoggerFactory.getLogger(DatabaseRoleGuard.class);

    /** Perfiles donde una configuración insegura impide arrancar. */
    static final Set<String> ENFORCED_PROFILES = Set.of("dev", "prod");

    static final String ROLE_SQL =
            "SELECT current_user, r.rolsuper, r.rolbypassrls FROM pg_roles r WHERE r.rolname = current_user";

    /**
     * Tablas del esquema public que el usuario actual "posee" para Postgres: es el dueño o
     * hereda los privilegios del dueño (miembro del rol dueño). En ambos casos RLS no aplica.
     */
    static final String OWNED_TABLES_SQL = """
            SELECT c.relname
            FROM pg_class c
            JOIN pg_namespace n ON n.oid = c.relnamespace
            WHERE n.nspname = 'public'
              AND c.relkind IN ('r', 'p')
              AND pg_has_role(current_user, c.relowner, 'USAGE')
            ORDER BY c.relname
            LIMIT 5
            """;

    private final DataSource dataSource;
    private final Environment environment;

    public DatabaseRoleGuard(DataSource dataSource, Environment environment) {
        this.dataSource = dataSource;
        this.environment = environment;
    }

    @Override
    public void afterSingletonsInstantiated() {
        verify();
    }

    /** Ejecuta las verificaciones; en perfiles exigidos lanza {@link IllegalStateException}. */
    void verify() {
        boolean enforced = isEnforced();
        List<String> problems = new ArrayList<>();

        String migrationUser = environment.getProperty("DB_MIGRATION_USER");
        boolean missingMigrationUser = migrationUser == null || migrationUser.isBlank();
        if (missingMigrationUser) {
            problems.add("falta DB_MIGRATION_USER: Flyway y la aplicación usarían el mismo usuario "
                    + "(el dueño de las tablas), y RLS no aplica al dueño");
        }

        try (Connection c = dataSource.getConnection(); Statement st = c.createStatement()) {
            String product = c.getMetaData().getDatabaseProductName();
            if (!"PostgreSQL".equalsIgnoreCase(product)) {
                problems.add("la base no es PostgreSQL (" + product + "): no hay RLS");
            } else {
                // Se compara con el usuario REAL de la conexión, no con la propiedad (que puede
                // venir de otro lado, p. ej. Testcontainers @ServiceConnection).
                String appUser = c.getMetaData().getUserName();
                if (!missingMigrationUser && appUser != null && migrationUser.trim().equalsIgnoreCase(appUser.trim())) {
                    problems.add("DB_MIGRATION_USER y DB_USER son el mismo usuario (" + appUser
                            + "): la aplicación tiene que conectarse con el rol <base>_app, no con el dueño");
                }
                problems.addAll(checkRole(st));
            }
        } catch (SQLException e) {
            problems.add("no se pudo verificar el rol de la base: " + e.getMessage());
        }

        if (problems.isEmpty()) {
            log.info("Guardia de RLS: OK (la aplicación se conecta con un rol sujeto a RLS)");
            return;
        }

        String detail = String.join("; ", problems);
        if (enforced) {
            throw new IllegalStateException("Guardia de RLS: el backend NO arranca en el perfil "
                    + Arrays.toString(environment.getActiveProfiles())
                    + " porque el aislamiento entre empresas quedaría apagado: " + detail
                    + ". Configurar DB_USER = rol <base>_app (sin superusuario, sin BYPASSRLS, sin ser dueño) "
                    + "y DB_MIGRATION_USER = dueño de las tablas.");
        }
        log.warn("Guardia de RLS (perfil {} — solo aviso, en dev/prod no arrancaría): {}",
                Arrays.toString(environment.getActiveProfiles()), detail);
    }

    private static List<String> checkRole(Statement st) throws SQLException {
        List<String> problems = new ArrayList<>();
        String user;
        try (ResultSet rs = st.executeQuery(ROLE_SQL)) {
            if (!rs.next()) {
                problems.add("no se encontró el rol actual en pg_roles");
                return problems;
            }
            user = rs.getString(1);
            if (rs.getBoolean("rolsuper")) {
                problems.add("el usuario '" + user + "' es SUPERUSER");
            }
            if (rs.getBoolean("rolbypassrls")) {
                problems.add("el usuario '" + user + "' tiene BYPASSRLS");
            }
        }
        List<String> owned = new ArrayList<>();
        try (ResultSet rs = st.executeQuery(OWNED_TABLES_SQL)) {
            while (rs.next()) owned.add(rs.getString(1));
        }
        if (!owned.isEmpty()) {
            problems.add("el usuario '" + user + "' es dueño (o miembro del rol dueño) de tablas del esquema, p. ej. "
                    + owned);
        }
        return problems;
    }

    private boolean isEnforced() {
        return Arrays.stream(environment.getActiveProfiles())
                .map(String::toLowerCase)
                .anyMatch(ENFORCED_PROFILES::contains);
    }
}
