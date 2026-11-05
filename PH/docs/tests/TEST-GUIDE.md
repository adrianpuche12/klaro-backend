# Guía de Tests — Proyecto Humberto V2 (Backend)

## Índice

1. [Visión general](#1-visión-general)
2. [Stack de testing](#2-stack-de-testing)
3. [Estructura de archivos](#3-estructura-de-archivos)
4. [Cómo ejecutar los tests](#4-cómo-ejecutar-los-tests)
5. [SalesServiceTest](#5-salessservicetest)
6. [ShiftServiceTest](#6-shiftservicetest)
7. [InventoryServiceTest](#7-inventoryservicetest)
8. [AppUserServiceTest](#8-appuserservicetest)
9. [Cobertura y objetivos](#9-cobertura-y-objetivos)
10. [Convenciones y patrones usados](#10-convenciones-y-patrones-usados)
11. [Roadmap — tests de integración](#11-roadmap--tests-de-integración)
13. [Permisos y aislamiento entre empresas (HTTP)](#13-permisos-y-aislamiento-entre-empresas-http)

---

## 1. Visión general

Los tests cubren la **capa de servicio** (lógica de negocio) de los cuatro módulos críticos del sistema:

| Módulo                  | Clase testeada                  | Tests | Tipo                        |
|-------------------------|---------------------------------|-------|-----------------------------|
| Ventas                  | `SalesService`                  | 16    | Unitario (Mockito)          |
| Turnos                  | `ShiftService`                  | 13    | Unitario (Mockito)          |
| Inventario              | `InventoryService`              | 12    | Unitario (Mockito)          |
| Usuarios                | `AppUserService`                | 19    | Unitario (Mockito)          |
| Ventas (HTTP)           | `SalesController`               | 11    | @WebMvcTest + MockMvc       |
| Turnos (HTTP)           | `ShiftController`               | 10    | @WebMvcTest + MockMvc       |
| Inventario (HTTP)       | `InventoryController`           | 9     | @WebMvcTest + MockMvc       |
| Usuarios (HTTP)         | `AppUserController`             | 20    | @WebMvcTest + MockMvc       |
| Inventario (repo)       | `InventoryStockRepository`      | 12    | @DataJpaTest + Testcontainers |
| Turnos (repo)           | `ShiftRepository`               | 9     | @DataJpaTest + Testcontainers |
| Ventas (repo)           | `SaleRepository`                | 9     | @DataJpaTest + Testcontainers |
| Contexto                | `BalanceApplicationTests`       | 1     | @SpringBootTest             |
| **Total**               |                                 | **141** |                           |

Todos son **tests unitarios puros** — no requieren base de datos, ni Keycloak, ni red. Se ejecutan en milisegundos.

---

## 2. Stack de testing

### Dependencias utilizadas

Todas incluidas en `spring-boot-starter-test` (ya en el pom.xml). **No se agregó ninguna dependencia nueva.**

| Librería              | Versión (heredada de Spring Boot 3.3.2) | Rol                                      |
|-----------------------|-----------------------------------------|------------------------------------------|
| **JUnit 5 (Jupiter)** | 5.10.x                                  | Framework de tests                       |
| **Mockito**           | 5.x                                     | Mocking de dependencias                  |
| **AssertJ**           | 3.x                                     | Assertions fluidas (`assertThat(...)`)   |
| **Mockito JUnit 5**   | incluido                                | Extensión `@ExtendWith(MockitoExtension.class)` |

### Por qué no se usó Testcontainers
Los tests unitarios mockean todos los repositorios con Mockito. No hay acceso real a base de datos. Esta decisión permite:
- Ejecución instantánea (< 500ms para los 48 tests)
- Sin dependencia de red ni infraestructura
- Fácil ejecución en CI/CD sin Docker

Para tests de integración (ver sección 11) se planea agregar Testcontainers 1.19 con PostgreSQL.

---

## 3. Estructura de archivos

```
src/test/
├── resources/
│   └── application-test.properties           ← eureka deshabilitado, ddl create-drop
└── java/balance/
    ├── BalanceApplicationTests.java              ← 1 test de contexto
    ├── sales/
    │   ├── service/
    │   │   ├── SalesServiceTest.java             ← 16 tests (unitarios, Mockito)
    │   │   └── ShiftServiceTest.java             ← 13 tests (unitarios, Mockito)
    │   ├── controller/
    │   │   ├── SalesControllerTest.java          ← 11 tests (@WebMvcTest)
    │   │   └── ShiftControllerTest.java          ← 10 tests (@WebMvcTest)
    │   └── repository/
    │       ├── ShiftRepositoryIT.java            ← 9 tests (@DataJpaTest + Testcontainers)
    │       └── SaleRepositoryIT.java             ← 9 tests (@DataJpaTest + Testcontainers)
    ├── inventory/
    │   ├── service/
    │   │   └── InventoryServiceTest.java         ← 12 tests (unitarios, Mockito)
    │   ├── controller/
    │   │   └── InventoryControllerTest.java      ← 9 tests (@WebMvcTest)
    │   └── repository/
    │       └── InventoryStockRepositoryIT.java   ← 12 tests (@DataJpaTest + Testcontainers)
    └── users/
        ├── service/
        │   └── AppUserServiceTest.java           ← 19 tests (unitarios, Mockito)
        └── controller/
            └── AppUserControllerTest.java        ← 20 tests (@WebMvcTest)

docs/tests/
├── TEST-GUIDE.md                         ← este archivo
└── COVERAGE-REPORT.md                    ← resumen de cobertura por método
```

---

## 4. Cómo ejecutar los tests

### Todos los tests
```bash
cd PH-Backend-v2/PH
mvn test
```

### Solo un módulo específico
```bash
mvn test -Dtest=SalesServiceTest
mvn test -Dtest=ShiftServiceTest
mvn test -Dtest=InventoryServiceTest
mvn test -Dtest=AppUserServiceTest
```

### Con reporte de cobertura (requiere agregar JaCoCo al pom.xml)
```bash
mvn test jacoco:report
# Reporte en: target/site/jacoco/index.html
```

### Desde IntelliJ IDEA
- Click derecho sobre `src/test/java/balance` → **Run Tests in 'balance'**
- O sobre un archivo específico → **Run 'SalesServiceTest'**

---

## 5. SalesServiceTest

**Archivo:** `src/test/java/balance/sales/service/SalesServiceTest.java`

**Clase bajo test:** `balance.sales.service.SalesService`

**Mocks usados:**
- `SaleRepository` — persistencia de ventas
- `ShiftRepository` — consulta del turno
- `ProductRepository` — consulta de productos
- `StoreRepository` — consulta de locales
- `InventoryService` — descuento/reversión de stock
- `FormsService` — creación de ClosingDeposit (sistema V1)

### Tests incluidos

| Test | Qué verifica |
|------|-------------|
| `createSale_calculatesIsvCorrectly` | ISV = subtotal × 0.15. Para precio=100, qty=2: subtotal=200, isv=30, total=230 |
| `createSale_isvIsExactly15Percent` | ISV es exactamente 15% con redondeo HALF_UP para precios fraccionarios |
| `createSale_savesProductNameAndPriceSnapshot` | El nombre y precio del producto se guardan como snapshot en SaleItem |
| `createSale_throwsWhenShiftNotFound` | `IllegalArgumentException` si el turno no existe |
| `createSale_throwsWhenShiftIsClosed` | `IllegalStateException` si el turno ya está CLOSED |
| `createSale_throwsWhenProductNotFound` | `IllegalArgumentException` si el producto no existe |
| `createSale_throwsWhenProductIsInactive` | `IllegalArgumentException` si el producto está inactivo |
| `createSale_callsAdjustSilentForEachItem` | Se llama `adjustSilent` una vez por cada producto en la venta |
| `createSale_adjustSilentUsesTipoSalida` | El ajuste de stock usa tipo "SALIDA" con la cantidad correcta |
| `cancelSale_deletesOpenSale` | La venta se elimina del repositorio al cancelar |
| `cancelSale_revertsStockWithEntrada` | Al cancelar, se llama `adjustSilent` con tipo "ENTRADA" para revertir stock |
| `cancelSale_throwsWhenSaleNotFound` | `IllegalArgumentException` si la venta no existe |
| `cancelSale_throwsWhenSaleIsConfirmed` | `IllegalStateException` si la venta ya fue confirmada (no se puede cancelar) |
| `closeShift_throwsWhenShiftAlreadyClosed` | `IllegalStateException` si el turno ya está cerrado |
| `closeShift_throwsWhenNoOpenSales` | `IllegalStateException` si no hay ventas abiertas para cerrar |
| `closeShift_confirmsAllOpenSalesAndSavesShift` | Todas las ventas pasan a CONFIRMED, el turno a CLOSED y closedAt se registra |

### Lógica crítica verificada

**Cálculo de ISV (15%):**
```
subtotal = precio × cantidad (por cada ítem, sumados)
isv      = subtotal × 0.15  (HALF_UP, 2 decimales)
total    = subtotal + isv
```

**Snapshot de precio:** El precio se captura al momento de la venta. Si el precio del producto cambia después, las ventas históricas conservan el precio original.

**adjustSilent en lugar de adjust:** Las ventas no se bloquean por falta de stock. `adjustSilent` captura la excepción internamente y continúa.

---

## 6. ShiftServiceTest

**Archivo:** `src/test/java/balance/sales/service/ShiftServiceTest.java`

**Clase bajo test:** `balance.sales.service.ShiftService`

**Mocks usados:**
- `ShiftRepository` — persistencia de turnos
- `StoreRepository` — consulta de locales

### Tests incluidos

| Test | Qué verifica |
|------|-------------|
| `openShift_codeMatchesExpectedPattern` | El código cumple el patrón `T-YYYYMMDD-HHmm-[A-Z]{1,3}` |
| `openShift_codeEndsWithFirstThreeLettersOfStoreName` | "Danli" → sufijo "-DAN" |
| `openShift_codeStripsNonLettersFromStoreName` | "El Paraiso" → solo letras → "ELP" → sufijo "-ELP" |
| `openShift_codeContainsTodayDate` | El código incluye la fecha actual en formato YYYYMMDD |
| `openShift_setsStatusToOpen` | El turno creado tiene status "OPEN" y el username correcto |
| `openShift_throwsWhenStoreNotFound` | `IllegalArgumentException` si el local no existe |
| `openShift_throwsWhenShiftAlreadyOpen` | `IllegalStateException` si ya hay un turno abierto para ese local |
| `closeShift_setsStatusToClosedAndRegistersClosedAt` | Status pasa a "CLOSED" y closedAt queda registrado |
| `closeShift_throwsWhenShiftNotFound` | `IllegalArgumentException` si el turno no existe |
| `closeShift_throwsWhenAlreadyClosed` | `IllegalStateException` si el turno ya estaba cerrado |
| `getActiveShift_returnsNullWhenNoActiveShift` | Retorna null si no hay turno abierto |
| `getActiveShift_returnsShiftWhenExists` | Retorna el turno activo cuando existe |
| `getById_throwsWhenNotFound` | `IllegalArgumentException` si el turno no existe |

### Formato del código de turno

```
T - YYYYMMDD - HHmm - [STORE]
│   │          │      └── Primeras 3 letras del local (solo alfanumérico, mayúsculas)
│   │          └── Hora y minuto de apertura (permite múltiples turnos/día)
│   └── Fecha de apertura
└── Prefijo fijo

Ejemplos:
  T-20260514-0900-DAN   (Danli, 9:00 AM, 14 mayo 2026)
  T-20260514-1435-ELP   (El Paraíso, 2:35 PM)
```

**Bug corregido (sesión anterior):** El formato original era `T-YYYYMMDD-DAN`, sin hora. Si se abrían dos turnos el mismo día lanzaba `UniqueConstraintViolation`. Se agregó `HHmm` para garantizar unicidad.

---

## 7. InventoryServiceTest

**Archivo:** `src/test/java/balance/inventory/service/InventoryServiceTest.java`

**Clase bajo test:** `balance.inventory.service.InventoryService`

**Mocks usados:**
- `InventoryStockRepository` — stock por producto/local
- `InventoryMovementRepository` — historial de movimientos
- `ProductRepository` — consulta de productos
- `StoreRepository` — consulta de locales
- `CategoryRepository` — resumen por categoría

### Tests incluidos

| Test | Qué verifica |
|------|-------------|
| `adjust_ENTRADA_increasesQuantityCorrectly` | ENTRADA suma la cantidad al stock existente |
| `adjust_SALIDA_decreasesQuantityCorrectly` | SALIDA resta la cantidad del stock |
| `adjust_SALIDA_throwsWhenStockInsufficient` | `IllegalArgumentException` cuando qty < cantidad solicitada |
| `adjust_SALIDA_throwsWhenQuantityExactlyExceedsStock` | Falla si se pide sacar 6 con stock=5 |
| `adjust_SALIDA_succeedsWhenQuantityEqualsStock` | Éxito si se saca exactamente todo el stock (resultado=0) |
| `adjustSilent_doesNotThrowWhenStockInsufficient` | `adjustSilent` no lanza excepción aunque el stock sea insuficiente |
| `adjustSilent_doesNotSaveWhenStockInsufficient` | `adjustSilent` no persiste nada si el ajuste falló |
| `adjust_registersMovementWithCorrectFields` | El movimiento registrado tiene tipo, cantidad, motivo, notas y username correctos |
| `adjust_alwaysRegistersMovementRegardlessOfType` | Siempre se registra un movimiento (ENTRADA, SALIDA o AJUSTE) |
| `adjust_createsStockRecordIfNotExists` | Si no existe registro de stock para el producto, se crea automáticamente |
| `adjust_throwsWhenStoreNotFound` | `IllegalArgumentException` si el local no existe |
| `adjust_throwsWhenProductNotFound` | `IllegalArgumentException` si el producto no existe |

### Lógica de stock crítica

**Tipos de ajuste:**
- `ENTRADA` → `newQty = qty + delta` (delta positivo)
- `SALIDA`  → `newQty = qty - delta` (delta negativo). Lanza si `newQty < 0`
- `AJUSTE`  → mismo que ENTRADA (suma)

**adjustSilent (usado por SalesService):**
```java
// No lanza excepción. Las ventas no se bloquean por falta de stock.
public void adjustSilent(Long storeId, StockAdjustmentDTO dto) {
    try { adjust(storeId, dto); } catch (IllegalArgumentException ignored) {}
}
```

**Bug corregido (sesión anterior):** La condición `lowStock` era `qty <= minStock`, lo que marcaba como "bajo stock" productos con `minStock=0` y `qty=0` (falso positivo). La condición correcta es `minStock > 0 && qty <= minStock`.

> **Nota:** El test de `lowStock` no está aquí porque esa lógica vive en el `@Query` del repositorio (`findLowStockByStoreId`). Para testearla correctamente se necesita un test de integración con base de datos real (ver sección 11).

---

## 8. AppUserServiceTest

**Archivo:** `src/test/java/balance/users/service/AppUserServiceTest.java`

**Clase bajo test:** `balance.users.service.AppUserService`

**Mocks usados:**
- `AppUserRepository` — persistencia de usuarios en BD
- `StoreRepository` — consulta de locales
- `KeycloakAdminService` — integración con Keycloak (siempre mockeado)

### Tests incluidos

| Test | Qué verifica |
|------|-------------|
| `create_normalizesUsernameToLowercase` | Username se convierte a lowercase antes de guardar |
| `create_trimesUsernameWhitespace` | Espacios al inicio/fin del username se eliminan |
| `create_savesKeycloakIdReturnedByKeycloak` | El keycloakId retornado por Keycloak se guarda en BD |
| `create_setsStatusToActiveByDefault` | El usuario se crea con status "ACTIVE" |
| `create_throwsWhenUsernameAlreadyExists` | `IllegalArgumentException` si el username ya está en uso |
| `create_throwsWhenStoreNotFound` | `IllegalArgumentException` si el local no existe; Keycloak no se llama |
| `suspend_changesStatusToSuspended` | Status pasa a "SUSPENDED" |
| `suspend_disablesUserInKeycloak` | Se llama `setUserEnabled(id, false)` en Keycloak |
| `suspend_throwsWhenUserAlreadySuspended` | `IllegalStateException` si ya está suspendido |
| `suspend_throwsWhenUserNotFound` | `IllegalArgumentException` si el usuario no existe |
| `activate_changesStatusToActive` | Status pasa a "ACTIVE" |
| `activate_enablesUserInKeycloak` | Se llama `setUserEnabled(id, true)` en Keycloak |
| `activate_throwsWhenUserAlreadyActive` | `IllegalStateException` si ya está activo |
| `reassign_changesUserStore` | El store del usuario se actualiza al nuevo local |
| `reassign_throwsWhenNewStoreNotFound` | `IllegalArgumentException` si el nuevo local no existe |
| `delete_removesUserFromKeycloakAndDatabase` | Se llama a Keycloak y a la BD para eliminar |
| `delete_callsKeycloakBeforeDatabase` | Keycloak se llama **antes** que la BD (orden garantizado con `inOrder`) |
| `findByUsername_normalizesToLowercase` | La búsqueda siempre usa lowercase |
| `findByUsername_throwsWhenNotFound` | `IllegalArgumentException` si el username no existe |

### Integración con Keycloak

`KeycloakAdminService` siempre se mockea en tests unitarios porque:
1. Keycloak no está disponible en el entorno de tests
2. El comportamiento de la lógica de negocio no depende de la respuesta HTTP de Keycloak
3. Permite verificar que los métodos correctos se llaman con los parámetros correctos

El test `delete_callsKeycloakBeforeDatabase` verifica el orden de operaciones usando `inOrder()` de Mockito — garantía de que si Keycloak falla, la BD no se modifica (con `@Transactional`).

---

## 9. Cobertura y objetivos

### Cobertura alcanzada (estimada por inspección)

| Servicio           | Métodos públicos | Cubiertos en tests | Cobertura estimada |
|--------------------|-----------------|--------------------|--------------------|
| `SalesService`     | 6               | 5                  | ~85%               |
| `ShiftService`     | 5               | 5                  | ~95%               |
| `InventoryService` | 7               | 4                  | ~75%               |
| `AppUserService`   | 8               | 8                  | ~95%               |

> Los métodos no cubiertos (`getDailySummary`, `getSummary`, `getMovements`, `getStock`) son consultas de solo lectura que no contienen lógica de negocio compleja.

### Qué escenarios se decidió NO testear aquí

| Escenario | Motivo |
|-----------|--------|
| Repositorios JPA | Deben testearse con `@DataJpaTest` + BD real |
| Queries con `@Query` (lowStock, findLowStockByStoreId) | Requieren BD real para validar SQL/JPQL |
| Controllers (endpoints HTTP) | Deben testearse con `@WebMvcTest` + MockMvc |
| KeycloakAdminService (HTTP real) | Requiere Keycloak levantado o WireMock |
| Transaccionalidad real (`@Transactional` rollback) | Requiere contexto Spring + BD real |

---

## 10. Convenciones y patrones usados

### Nomenclatura de tests
```
{método}_{condición}_{resultado}

Ejemplos:
  createSale_calculatesIsvCorrectly
  adjust_SALIDA_throwsWhenStockInsufficient
  openShift_codeEndsWithFirstThreeLettersOfStoreName
```

### Estructura AAA (Arrange-Act-Assert)
```java
@Test
void createSale_calculatesIsvCorrectly() {
    // Arrange — preparar mocks y datos
    when(shiftRepository.findById(1L)).thenReturn(Optional.of(buildShift(1L, "OPEN")));
    when(productRepository.findById(1L)).thenReturn(Optional.of(buildProduct(1L, "Pollo", new BigDecimal("100.00"))));
    when(saleRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

    // Act — ejecutar el método bajo test
    SaleResponseDTO result = salesService.createSale(1L, buildRequest("cajero01", 1L, 2));

    // Assert — verificar el resultado
    assertThat(result.getSubtotal()).isEqualByComparingTo("200.00");
    assertThat(result.getIsv()).isEqualByComparingTo("30.00");
}
```

### Helpers privados
Cada clase de test tiene helpers para construir entidades sin repetir código:
```java
private Store buildStore(Long id, String name) { ... }
private Shift buildShift(Long id, String status) { ... }
private Product buildProduct(Long id, String name, BigDecimal price) { ... }
```

### Verificación de BigDecimal
Siempre usar `isEqualByComparingTo` (no `isEqualTo`) para comparar BigDecimal — evita falsos negativos por diferencias de escala:
```java
// Correcto:
assertThat(result.getIsv()).isEqualByComparingTo("30.00");

// INCORRECTO — puede fallar si la escala interna es diferente:
assertThat(result.getIsv()).isEqualTo(new BigDecimal("30.00"));
```

### Verificación de orden con inOrder
Para verificar que las operaciones ocurren en el orden correcto:
```java
var inOrder = inOrder(keycloakAdmin, userRepository);
appUserService.delete(1L);
inOrder.verify(keycloakAdmin).deleteUser("kc-uuid-1");  // primero
inOrder.verify(userRepository).delete(user);            // después
```

---

## 11. Tests de repositorio (@DataJpaTest + Testcontainers)

**Archivos:**
- `src/test/java/balance/inventory/repository/InventoryStockRepositoryIT.java`
- `src/test/java/balance/sales/repository/ShiftRepositoryIT.java`
- `src/test/java/balance/sales/repository/SaleRepositoryIT.java`

**Requisito:** Docker Desktop corriendo en la máquina. Testcontainers levanta un contenedor `postgres:16-alpine` por clase de test y lo destruye al terminar.

**Configuración:**
```java
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Testcontainers
@TestPropertySource(locations = "classpath:application-test.properties")
class InventoryStockRepositoryIT {
    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine");
```

- `replace = NONE` → no reemplaza con H2, usa el container real
- `@ServiceConnection` → Spring Boot 3.1+ configura automáticamente la datasource del container
- `application-test.properties` → desactiva Eureka y usa `ddl-auto=create-drop`

### InventoryStockRepositoryIT (12 tests)

| Test | Qué verifica |
|------|-------------|
| `findLowStockByStoreId_returnsProductBelowMinStock` | Producto con qty < minStock aparece en la lista |
| `findLowStockByStoreId_excludesProductAboveMinStock` | Producto con qty > minStock NO aparece |
| `findLowStockByStoreId_excludesProductWithMinStockZero` | **Bug fix:** minStock=0, qty=0 NO es bajo stock |
| `findLowStockByStoreId_excludesProductWithMinStockZeroAndPositiveQuantity` | minStock=0 nunca es bajo stock |
| `findLowStockByStoreId_includesProductAtExactMinStock` | qty == minStock SÍ es bajo stock |
| `findLowStockByStoreId_returnsOnlyLowStockAmongMultipleProducts` | Con 3 productos, solo retorna el bajo stock |
| `findLowStockByStoreId_onlyReturnsProductsForRequestedStore` | No mezcla productos de otros locales |
| `countLowStockByStoreId_returnsCorrectCount` | Cuenta correctamente los productos bajo stock |
| `countLowStockByStoreId_returnsZeroWhenNoLowStock` | Retorna 0 cuando no hay bajo stock |
| `findByProductIdAndStoreId_returnsStockWhenExists` | Encuentra el registro de stock por producto+local |
| `findByProductIdAndStoreId_returnsEmptyWhenNotExists` | Retorna Optional.empty() cuando no existe |
| `findByStoreIdOrderByProductNameAsc_returnsInAlphabeticOrder` | Orden alfabético verificado con 3 productos |

**Bug corregido junto con los tests:** La query `findLowStockByStoreId` y `countLowStockByStoreId` ahora incluyen `AND s.product.minStock > 0`, alineando el criterio con `StockItemDTO.from()` que ya tenía el fix. Antes, productos con `minStock=0` y `quantity=0` aparecían como "bajo stock" por falso positivo (`0 <= 0 = true`).

### ShiftRepositoryIT (9 tests)

| Test | Qué verifica |
|------|-------------|
| `existsByStoreIdAndStatus_returnsTrueWhenOpenShiftExists` | Detecta turno OPEN existente |
| `existsByStoreIdAndStatus_returnsFalseWhenNoOpenShift` | Turno CLOSED no cuenta como OPEN |
| `existsByStoreIdAndStatus_returnsFalseWhenStoreHasNoShifts` | Sin turnos → false |
| `existsByStoreIdAndStatus_returnsFalseForDifferentStore` | No mezcla turnos de otros locales |
| `findByStoreIdAndStatus_returnsOpenShift` | Retorna el turno OPEN con su código |
| `findByStoreIdAndStatus_returnsEmptyWhenShiftIsClosed` | CLOSED no matchea OPEN |
| `findByStoreIdOrderByOpenedAtDesc_returnsMostRecentFirst` | Orden descendente por fecha de apertura |
| `findByStoreIdOrderByOpenedAtDesc_returnsEmptyWhenNoShifts` | Lista vacía cuando no hay turnos |
| `findByStoreIdOrderByOpenedAtDesc_onlyReturnsShiftsForRequestedStore` | Aislamiento por local |

### SaleRepositoryIT (9 tests)

| Test | Qué verifica |
|------|-------------|
| `findOpenByShiftId_returnsOnlyOpenSales` | Solo ventas OPEN, excluye CONFIRMED |
| `findOpenByShiftId_returnsEmptyWhenAllSalesConfirmed` | Todas confirmadas → vacío |
| `findOpenByShiftId_returnsEmptyWhenNoSales` | Sin ventas → vacío |
| `findOpenByShiftId_onlyReturnsFromRequestedShift` | No mezcla ventas de otros turnos |
| `countOpenByShiftId_returnsCorrectCount` | Cuenta solo ventas OPEN |
| `countOpenByShiftId_returnsZeroWhenNoOpenSales` | 0 cuando todas están CONFIRMED |
| `findByShiftIdOrderByCreatedAtDesc_returnsAllSalesRegardlessOfStatus` | Retorna OPEN y CONFIRMED |
| `findByShiftIdOrderByCreatedAtDesc_returnsEmptyForUnknownShift` | Shift inexistente → vacío |
| `findByShiftIdAndStatus_filtersCorrectly` | Filtra por OPEN y por CONFIRMED independientemente |

---

## 12. Roadmap — próximos tests opcionales

Las tres capas principales están cubiertas. Lo que queda es opcional:

### Tests end-to-end (`@SpringBootTest` + Testcontainers)
Requieren levantar el contexto completo de Spring:
- Flujo completo: abrir turno → crear venta → cerrar turno → verificar ClosingDeposit creado en V1
- Flujo de inventario: crear producto → ajustar stock → verificar movimiento registrado

### Tests frontend (Jest)
- `numberFormat.test.ts` — funciones `formatCurrency`, `formatAmountInput`, `parseFormattedNumber`
- Snapshot tests de componentes `KpiCard`, `AppButton`, `AppBadge`

### KeycloakAdminService (WireMock)
Requiere agregar `wiremock-standalone` como dependencia de test:
- Simular respuestas HTTP de Keycloak para `createUser`, `setUserEnabled`, `deleteUser`

---

## 13. Permisos y aislamiento entre empresas (HTTP)

Tests en `PH/src/test/java/balance/security/http/`. Levantan Spring completo con Postgres
(Testcontainers) y llaman a la API con tokens de 4 roles de la empresa A (`fixture.sql`).

| Test | Qué verifica | Cuándo corre |
|---|---|---|
| `PermissionMatrixIT` | Recorre `PermissionMatrix`: rol × endpoint → `OK` (2xx), `403` o `-` (no aplica). Ninguna celda admite 5xx. | `mvn verify` (Failsafe), en el CI de cada PR |
| `CrossTenantHttpIT` | Recorre `CrossTenantCatalog`: un usuario de la empresa B nunca lee ni modifica datos de A. | `mvn verify` |
| `EndpointCoverageTest` | Por reflexión, todo endpoint de un `@RestController` tiene al menos una fila en la matriz y un caso entre empresas (o está excluido con motivo). | `mvn test`, sin Docker |

### Agregar o cambiar un endpoint

1. Agregar su fila en `PermissionMatrix` con el resultado esperado para root, encargado, cajero y contador.
2. Agregar su caso en `CrossTenantCatalog`.
3. Si no, `EndpointCoverageTest` falla. Excluir un endpoint solo con un motivo escrito.

### Lecturas compartidas entre módulos: `assertAccessAny`

`PermissionGuard.assertAccessAny(storeId, modulo1, modulo2, ...)` permite el acceso si el rol
tiene **alguno** de los módulos, y además exige que el local esté asignado al usuario. Se usa
cuando una pantalla necesita leer datos de otro módulo sin poder modificarlos:

- `GET /api/v2/stores/{id}/categories` → `CATALOG` **o** `POS`.
- `GET /api/v2/stores/{id}/stock` → `INVENTORY` **o** `POS`.

Así un cajero con solo `POS` ve el catálogo y el stock de su local, pero no puede ajustar stock
ni editar categorías (esos endpoints siguen con `assertManage` del módulo propio) ni leer un local
no asignado (fila `GET /api/v2/stores/3/categories` → `403` para los roles acotados).

Regla: no dar un módulo de más a un rol para que una pantalla funcione. Si una pantalla necesita
leer datos de otro módulo, se resuelve en el endpoint con `assertAccessAny` y su fila en la matriz.
