package balance.tenant.db;

import balance.tenant.context.TenantContext;
import org.springframework.jdbc.datasource.DelegatingDataSource;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;

/**
 * Propaga el tenant del request a Postgres para que las políticas de Row Level
 * Security (migración V11) filtren las filas.
 *
 * En cada checkout del pool setea la variable de sesión {@code app.tenant_id}
 * con el tenant de {@link TenantContext}, o vacía si no hay tenant (las
 * políticas devuelven entonces cero filas: fail-closed).
 *
 * Se setea en cada checkout, no por transacción, por dos motivos:
 * <ul>
 *   <li>cubre también las lecturas fuera de transacción (lazy loading con
 *       open-in-view), que de otro modo verían cero filas;</li>
 *   <li>una conexión devuelta al pool nunca conserva el tenant anterior,
 *       porque el próximo checkout lo sobreescribe siempre.</li>
 * </ul>
 */
public class TenantAwareDataSource extends DelegatingDataSource {

    static final String SET_TENANT_SQL = "SELECT set_config('app.tenant_id', ?, false)";

    public TenantAwareDataSource(DataSource target) {
        super(target);
    }

    @Override
    public Connection getConnection() throws SQLException {
        return applyTenant(super.getConnection());
    }

    @Override
    public Connection getConnection(String username, String password) throws SQLException {
        return applyTenant(super.getConnection(username, password));
    }

    private Connection applyTenant(Connection connection) throws SQLException {
        Long tenantId = TenantContext.getTenantId();
        try (PreparedStatement ps = connection.prepareStatement(SET_TENANT_SQL)) {
            ps.setString(1, tenantId == null ? "" : tenantId.toString());
            ps.execute();
        } catch (SQLException e) {
            // Nunca entregar una conexión con un tenant desconocido.
            connection.close();
            throw e;
        }
        return connection;
    }
}
