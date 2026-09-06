package balance.users.model;

/**
 * Módulos de negocio que un usuario con perfil acotado puede tener habilitados.
 * Los primeros 8 son 1:1 con los módulos reales de PH v2 (@ElementCollection de
 * strings, no un enum — se guarda el .name() como valor libre en user_permissions
 * para no atar el schema a este enum).
 * OPERATIONS es específico de Belopia (OperationsV3Controller no existe en PH v2).
 */
public enum PermissionModule {
    DASHBOARD,
    POS,
    SALES_HISTORY,
    INVENTORY,
    TRANSACTIONS,
    SALARY_PAYMENTS,
    SUPPLIER_PAYMENTS,
    CATALOG,
    OPERATIONS
}
