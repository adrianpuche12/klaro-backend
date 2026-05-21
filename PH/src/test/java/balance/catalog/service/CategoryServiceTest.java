package balance.catalog.service;

import balance.catalog.dto.CategoryRequestDTO;
import balance.catalog.model.Category;
import balance.catalog.repository.CategoryRepository;
import balance.model.Store;
import balance.repository.StoreRepository;
import balance.tenant.context.TenantContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class CategoryServiceTest {

    private static final Long TENANT_ID  = 1L;
    private static final Long STORE_ID   = 10L;
    private static final Long PARENT_ID  = 100L;
    private static final Long CHILD_ID   = 101L;

    @InjectMocks private CategoryService categoryService;
    @Mock private CategoryRepository    categoryRepository;
    @Mock private StoreRepository       storeRepository;

    @BeforeEach void setTenant()   { TenantContext.setTenantId(TENANT_ID); }
    @AfterEach  void clearTenant() { TenantContext.clear(); }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private Store buildStore(Long id) {
        Store s = new Store();
        s.setId(id);
        s.setName("Local Central");
        s.setTenantId(TENANT_ID);
        s.setActive(true);
        return s;
    }

    private Category buildCategory(Long id, String name, Category parent) {
        Category c = new Category();
        c.setId(id);
        c.setName(name);
        c.setActive(true);
        c.setDisplayOrder(0);
        c.setStore(buildStore(STORE_ID));
        c.setParent(parent);
        c.setTenantId(TENANT_ID);
        return c;
    }

    private CategoryRequestDTO buildRequest(String name) {
        CategoryRequestDTO dto = new CategoryRequestDTO();
        dto.setName(name);
        dto.setDescription("Descripcion de " + name);
        return dto;
    }

    // ── getTree ───────────────────────────────────────────────────────────────

    @Test
    void getTree_returnsEmpty_whenStoreHasNoCategories() {
        Store store = buildStore(STORE_ID);
        when(storeRepository.findByIdAndTenantId(STORE_ID, TENANT_ID)).thenReturn(Optional.of(store));
        when(categoryRepository.findAllByStoreId(STORE_ID)).thenReturn(List.of());
        // countProductsByCategoryIds no se llama cuando la lista está vacía (if (!catIds.isEmpty()))

        var result = categoryService.getTree(STORE_ID);

        assertThat(result).isEmpty();
    }

    @Test
    void getTree_returnsFlatRoots_whenNoChildren() {
        Store store = buildStore(STORE_ID);
        when(storeRepository.findByIdAndTenantId(STORE_ID, TENANT_ID)).thenReturn(Optional.of(store));

        Category root1 = buildCategory(1L, "Platos", null);
        Category root2 = buildCategory(2L, "Bebidas", null);
        when(categoryRepository.findAllByStoreId(STORE_ID)).thenReturn(List.of(root1, root2));
        lenient().when(categoryRepository.countProductsByCategoryIds(any())).thenReturn(List.of());

        var result = categoryService.getTree(STORE_ID);

        assertThat(result).hasSize(2);
        assertThat(result).extracting("name").containsExactlyInAnyOrder("Platos", "Bebidas");
    }

    @Test
    void getTree_buildsParentChildRelationship() {
        Store store = buildStore(STORE_ID);
        when(storeRepository.findByIdAndTenantId(STORE_ID, TENANT_ID)).thenReturn(Optional.of(store));

        Category root  = buildCategory(PARENT_ID, "Platos", null);
        Category child = buildCategory(CHILD_ID, "Entradas", root);
        when(categoryRepository.findAllByStoreId(STORE_ID)).thenReturn(List.of(root, child));
        lenient().when(categoryRepository.countProductsByCategoryIds(any())).thenReturn(List.of());

        var result = categoryService.getTree(STORE_ID);

        // Solo el root aparece en el nivel superior
        assertThat(result).hasSize(1);
        assertThat(result.get(0).getName()).isEqualTo("Platos");
        assertThat(result.get(0).getChildren()).hasSize(1);
        assertThat(result.get(0).getChildren().get(0).getName()).isEqualTo("Entradas");
    }

    @Test
    void getTree_throwsWhenStoreNotFoundForTenant() {
        when(storeRepository.findByIdAndTenantId(99L, TENANT_ID)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> categoryService.getTree(99L))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Local no encontrado");
    }

    // ── findById ──────────────────────────────────────────────────────────────

    @Test
    void findById_returnsEmpty_whenCategoryFromOtherTenant() {
        Category otherTenantCat = buildCategory(1L, "Ajena", null);
        otherTenantCat.setTenantId(99L); // otro tenant
        when(categoryRepository.findById(1L)).thenReturn(Optional.of(otherTenantCat));
        // countProductsByCategoryId no se llama porque el filter por tenant rechaza antes de toDTO

        var result = categoryService.findById(1L);

        assertThat(result).isEmpty(); // filtro por tenantId rechaza la categoría
    }

    @Test
    void findById_returnsDto_whenCorrectTenant() {
        Category cat = buildCategory(1L, "Platos", null);
        when(categoryRepository.findById(1L)).thenReturn(Optional.of(cat));
        when(categoryRepository.countProductsByCategoryId(1L)).thenReturn(3L);

        var result = categoryService.findById(1L);

        assertThat(result).isPresent();
        assertThat(result.get().getName()).isEqualTo("Platos");
        assertThat(result.get().getProductCount()).isEqualTo(3L);
    }

    // ── createRoot ────────────────────────────────────────────────────────────

    @Test
    void createRoot_setsTenantIdAndNullParent() {
        Store store = buildStore(STORE_ID);
        when(storeRepository.findByIdAndTenantId(STORE_ID, TENANT_ID)).thenReturn(Optional.of(store));
        Category saved = buildCategory(1L, "Platos", null);
        when(categoryRepository.save(any())).thenReturn(saved);
        when(categoryRepository.countProductsByCategoryId(anyLong())).thenReturn(0L);

        categoryService.createRoot(STORE_ID, buildRequest("Platos"));

        ArgumentCaptor<Category> captor = ArgumentCaptor.forClass(Category.class);
        verify(categoryRepository).save(captor.capture());
        assertThat(captor.getValue().getTenantId()).isEqualTo(TENANT_ID);
        assertThat(captor.getValue().getParent()).isNull();
        assertThat(captor.getValue().getActive()).isTrue();
    }

    @Test
    void createRoot_trimesName() {
        Store store = buildStore(STORE_ID);
        when(storeRepository.findByIdAndTenantId(STORE_ID, TENANT_ID)).thenReturn(Optional.of(store));
        Category saved = buildCategory(1L, "Platos Principales", null);
        when(categoryRepository.save(any())).thenReturn(saved);
        when(categoryRepository.countProductsByCategoryId(anyLong())).thenReturn(0L);

        categoryService.createRoot(STORE_ID, buildRequest("  Platos Principales  "));

        ArgumentCaptor<Category> captor = ArgumentCaptor.forClass(Category.class);
        verify(categoryRepository).save(captor.capture());
        assertThat(captor.getValue().getName()).isEqualTo("Platos Principales");
    }

    @Test
    void createRoot_throwsWhenStoreNotFound() {
        when(storeRepository.findByIdAndTenantId(99L, TENANT_ID)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> categoryService.createRoot(99L, buildRequest("Test")))
                .isInstanceOf(IllegalArgumentException.class);
    }

    // ── createChild ───────────────────────────────────────────────────────────

    @Test
    void createChild_setsParentCorrectly() {
        Store store = buildStore(STORE_ID);
        Category parent = buildCategory(PARENT_ID, "Platos", null);
        when(storeRepository.findByIdAndTenantId(STORE_ID, TENANT_ID)).thenReturn(Optional.of(store));
        when(categoryRepository.findById(PARENT_ID)).thenReturn(Optional.of(parent));
        Category saved = buildCategory(CHILD_ID, "Entradas", parent);
        when(categoryRepository.save(any())).thenReturn(saved);
        when(categoryRepository.countProductsByCategoryId(anyLong())).thenReturn(0L);

        var result = categoryService.createChild(STORE_ID, PARENT_ID, buildRequest("Entradas"));

        assertThat(result).isPresent();
        ArgumentCaptor<Category> captor = ArgumentCaptor.forClass(Category.class);
        verify(categoryRepository).save(captor.capture());
        assertThat(captor.getValue().getParent()).isEqualTo(parent);
    }

    @Test
    void createChild_returnsEmpty_whenParentFromOtherTenant() {
        Store store = buildStore(STORE_ID);
        Category foreignParent = buildCategory(PARENT_ID, "Foreign", null);
        foreignParent.setTenantId(99L); // otro tenant
        when(storeRepository.findByIdAndTenantId(STORE_ID, TENANT_ID)).thenReturn(Optional.of(store));
        when(categoryRepository.findById(PARENT_ID)).thenReturn(Optional.of(foreignParent));

        var result = categoryService.createChild(STORE_ID, PARENT_ID, buildRequest("Test"));

        assertThat(result).isEmpty();
        verify(categoryRepository, never()).save(any());
    }

    @Test
    void createChild_returnsEmpty_whenParentNotFound() {
        Store store = buildStore(STORE_ID);
        when(storeRepository.findByIdAndTenantId(STORE_ID, TENANT_ID)).thenReturn(Optional.of(store));
        when(categoryRepository.findById(99L)).thenReturn(Optional.empty());

        var result = categoryService.createChild(STORE_ID, 99L, buildRequest("Test"));

        assertThat(result).isEmpty();
    }

    // ── update ────────────────────────────────────────────────────────────────

    @Test
    void update_updatesNameAndDescription() {
        Category cat = buildCategory(1L, "Viejo Nombre", null);
        when(categoryRepository.findById(1L)).thenReturn(Optional.of(cat));
        when(categoryRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
        when(categoryRepository.countProductsByCategoryId(anyLong())).thenReturn(0L);

        CategoryRequestDTO dto = buildRequest("Nuevo Nombre");
        dto.setDescription("Nueva descripcion");
        dto.setDisplayOrder(5);
        var result = categoryService.update(1L, dto);

        assertThat(result).isPresent();
        assertThat(cat.getName()).isEqualTo("Nuevo Nombre");
        assertThat(cat.getDescription()).isEqualTo("Nueva descripcion");
        assertThat(cat.getDisplayOrder()).isEqualTo(5);
    }

    @Test
    void update_returnsEmpty_whenOtherTenantCategory() {
        Category cat = buildCategory(1L, "Test", null);
        cat.setTenantId(99L);
        when(categoryRepository.findById(1L)).thenReturn(Optional.of(cat));

        var result = categoryService.update(1L, buildRequest("Hack"));

        assertThat(result).isEmpty();
        verify(categoryRepository, never()).save(any());
    }

    // ── toggle ────────────────────────────────────────────────────────────────

    @Test
    void toggle_deactivatesActiveCategory() {
        Category cat = buildCategory(1L, "Platos", null);
        cat.setActive(true);
        when(categoryRepository.findById(1L)).thenReturn(Optional.of(cat));
        when(categoryRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
        when(categoryRepository.countProductsByCategoryId(anyLong())).thenReturn(0L);

        categoryService.toggle(1L);

        assertThat(cat.getActive()).isFalse();
    }

    @Test
    void toggle_activatesInactiveCategory() {
        Category cat = buildCategory(1L, "Platos", null);
        cat.setActive(false);
        when(categoryRepository.findById(1L)).thenReturn(Optional.of(cat));
        when(categoryRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
        when(categoryRepository.countProductsByCategoryId(anyLong())).thenReturn(0L);

        categoryService.toggle(1L);

        assertThat(cat.getActive()).isTrue();
    }

    @Test
    void toggle_returnsEmpty_whenOtherTenantCategory() {
        Category cat = buildCategory(1L, "Test", null);
        cat.setTenantId(99L);
        when(categoryRepository.findById(1L)).thenReturn(Optional.of(cat));

        var result = categoryService.toggle(1L);

        assertThat(result).isEmpty();
    }

    // ── delete ────────────────────────────────────────────────────────────────

    @Test
    void delete_returnsTrueAndDeletes_whenCorrectTenant() {
        Category cat = buildCategory(1L, "Platos", null);
        when(categoryRepository.findById(1L)).thenReturn(Optional.of(cat));

        boolean result = categoryService.delete(1L);

        assertThat(result).isTrue();
        verify(categoryRepository).deleteById(1L);
    }

    @Test
    void delete_returnsFalse_whenCategoryNotFound() {
        when(categoryRepository.findById(99L)).thenReturn(Optional.empty());

        assertThat(categoryService.delete(99L)).isFalse();
        verify(categoryRepository, never()).deleteById(any());
    }

    @Test
    void delete_returnsFalse_whenOtherTenantCategory() {
        Category cat = buildCategory(1L, "Test", null);
        cat.setTenantId(99L);
        when(categoryRepository.findById(1L)).thenReturn(Optional.of(cat));

        assertThat(categoryService.delete(1L)).isFalse();
        verify(categoryRepository, never()).deleteById(any());
    }
}
