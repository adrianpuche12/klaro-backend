package balance.common.enums;

/** Estado de acceso del usuario a la aplicación. */
public enum AppUserStatus {
    /** Usuario activo — puede iniciar sesión. */
    ACTIVE,
    /** Usuario suspendido — no puede iniciar sesión. */
    SUSPENDED,
    /** Usuario eliminado (soft-delete, SPRINT-14) — nunca se borra la fila,
     * se conserva para trazabilidad (created_by, historial de ventas, etc). */
    DELETED
}
