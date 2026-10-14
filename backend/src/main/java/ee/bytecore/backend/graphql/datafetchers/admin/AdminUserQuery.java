package ee.bytecore.backend.graphql.datafetchers.admin;

import org.springframework.security.access.prepost.PreAuthorize;

import ee.bytecore.backend.enums.UserRole;
import ee.bytecore.backend.graphql.mappers.UserInputMapper;
import ee.bytecore.backend.graphql.mappers.UserMapper;
import ee.bytecore.backend.services.UserService;

import com.netflix.dgs.codegen.generated.types.AdminUserPage;
import com.netflix.dgs.codegen.generated.types.Role;
import com.netflix.graphql.dgs.DgsComponent;
import com.netflix.graphql.dgs.DgsQuery;
import com.netflix.graphql.dgs.InputArgument;

@DgsComponent
public class AdminUserQuery {
    private final UserService userService;

    public AdminUserQuery(UserService userService) {
        this.userService = userService;
    }

    @DgsQuery
    @PreAuthorize("hasAuthority('SCOPE_' + T(ee.bytecore.backend.security.Scopes).USER_READ) && hasRole('ADMIN')")
    public AdminUserPage adminUsers(
            @InputArgument String query,
            @InputArgument Role role,
            @InputArgument Integer page,
            @InputArgument Integer size) {
        var result = userService.searchActive(
                query,
                role == null ? null : UserInputMapper.mapEnum(UserRole.class, role),
                page == null ? 0 : page,
                size == null ? 25 : size);
        return AdminUserPage.newBuilder()
                .items(result.getContent().stream()
                        .map(UserMapper::toGraphQlType)
                        .toList())
                .totalElements((int) Math.min(Integer.MAX_VALUE, result.getTotalElements()))
                .page(result.getNumber())
                .size(result.getSize())
                .build();
    }
}
