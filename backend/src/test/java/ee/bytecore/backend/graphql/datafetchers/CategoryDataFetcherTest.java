package ee.bytecore.backend.graphql.datafetchers;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Optional;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.graphql.test.autoconfigure.tester.AutoConfigureHttpGraphQlTester;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.graphql.test.tester.GraphQlTester;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import ee.bytecore.backend.entities.category.Category;
import ee.bytecore.backend.graphql.category.CategoryDataFetcher;
import ee.bytecore.backend.graphql.scalars.GraphQLConfig;
import ee.bytecore.backend.graphql.scalars.InstantScalar;
import ee.bytecore.backend.graphql.scalars.LocalDateScalar;
import ee.bytecore.backend.repositories.category.CategoryRepository;

import com.netflix.graphql.dgs.test.EnableDgsMockMvcTest;
import org.intellij.lang.annotations.Language;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

@SpringBootTest(
        classes = {CategoryDataFetcher.class, GraphQLConfig.class, LocalDateScalar.class, InstantScalar.class})
@EnableDgsMockMvcTest
@AutoConfigureHttpGraphQlTester
@Tag("graphql")
class CategoryDataFetcherTest {

    @MockitoBean
    CategoryRepository categoryRepository;

    private Category category;

    @Autowired
    private GraphQlTester graphQlTester;

    @BeforeEach
    void setUp() {
        category = Category.create("Shoes", "shoes", null);
    }

    @Test
    @WithMockUser
    void shouldReturnCategoryByIdTest() {
        Long id = category.getId();
        when(categoryRepository.findById(id)).thenReturn(Optional.of(category));

        @Language("GraphQl")
        var query =
                """
            query($id: ID!) {
              category(id: $id) {
                name
                slug
              }
            }
        """;

        graphQlTester
                .document(query)
                .variable("id", id)
                .execute()
                .path("category.name")
                .entity(String.class)
                .isEqualTo(category.getName())
                .path("category.slug")
                .entity(String.class)
                .isEqualTo(category.getSlug());
    }

    @Test
    @WithMockUser
    void shouldReturnCategoryBySlugTest() {
        when(categoryRepository.findBySlug("shoes")).thenReturn(Optional.of(category));

        @Language("GraphQl")
        var query =
                """
            query($slug: String!) {
              category(slug: $slug) {
                name
              }
            }
        """;

        graphQlTester
                .document(query)
                .variable("slug", "shoes")
                .execute()
                .path("category.name")
                .entity(String.class)
                .isEqualTo(category.getName());
    }

    @Test
    @WithMockUser
    void shouldReturnNullWhenCategoryNotFoundBySlugTest() {
        when(categoryRepository.findBySlug("missing")).thenReturn(Optional.empty());

        @Language("GraphQl")
        var query =
                """
            query($slug: String!) {
              category(slug: $slug) {
                name
              }
            }
        """;

        graphQlTester
                .document(query)
                .variable("slug", "missing")
                .execute()
                .path("category")
                .valueIsNull();
    }

    @Test
    @WithMockUser
    void shouldReturnAllCategoriesTest() {
        when(categoryRepository.findAll()).thenReturn(List.of(category));

        @Language("GraphQl")
        var query =
                """
            query {
              categories {
                name
                slug
              }
            }
        """;

        graphQlTester
                .document(query)
                .execute()
                .path("categories[0].name")
                .entity(String.class)
                .isEqualTo(category.getName())
                .path("categories[0].slug")
                .entity(String.class)
                .isEqualTo(category.getSlug());
    }

    @Test
    @WithMockUser
    void shouldReturnEmptyCategoriesListTest() {
        when(categoryRepository.findAll()).thenReturn(List.of());

        @Language("GraphQl")
        var query =
                """
            query {
              categories {
                name
              }
            }
        """;

        graphQlTester.document(query).execute().path("categories").entityList(Object.class).hasSize(0);
    }

    @Test
    @WithMockUser
    void shouldCreateCategoryTest() {
        when(categoryRepository.save(any(Category.class))).thenReturn(category);

        @Language("GraphQl")
        var mutation =
                """
            mutation {
              createCategory(input: { name: "Shoes", slug: "shoes" }) {
                name
                slug
              }
            }
        """;

        graphQlTester
                .document(mutation)
                .execute()
                .path("createCategory.name")
                .entity(String.class)
                .isEqualTo(category.getName())
                .path("createCategory.slug")
                .entity(String.class)
                .isEqualTo(category.getSlug());
    }

    @Test
    @WithMockUser
    void shouldUpdateCategoryTest() {
        Long id = category.getId();
        when(categoryRepository.findById(id)).thenReturn(Optional.of(category));
        when(categoryRepository.save(category)).thenReturn(category);

        @Language("GraphQl")
        var mutation =
                """
            mutation($id: ID!) {
              updateCategory(categoryId: $id, input: { name: "Sneakers" }) {
                name
              }
            }
        """;

        graphQlTester
                .document(mutation)
                .variable("id", id)
                .execute()
                .path("updateCategory.name")
                .entity(String.class)
                .isEqualTo("Sneakers");
    }

    @Test
    @WithMockUser
    void shouldDeleteCategoryTest() {
        Long id = category.getId();
        when(categoryRepository.findById(id)).thenReturn(Optional.of(category));

        @Language("GraphQl")
        var mutation =
                """
            mutation($id: ID!) {
              deleteCategory(categoryId: $id)
            }
        """;

        graphQlTester
                .document(mutation)
                .variable("id", id)
                .execute()
                .path("deleteCategory")
                .entity(Boolean.class)
                .isEqualTo(true);
    }

    @Test
    @WithMockUser
    void shouldReturnFalseWhenDeletingMissingCategoryTest() {
        Long id = 999L;
        when(categoryRepository.findById(id)).thenReturn(Optional.empty());

        @Language("GraphQl")
        var mutation =
                """
            mutation($id: ID!) {
              deleteCategory(categoryId: $id)
            }
        """;

        graphQlTester
                .document(mutation)
                .variable("id", id)
                .execute()
                .path("deleteCategory")
                .entity(Boolean.class)
                .isEqualTo(false);
    }
}
