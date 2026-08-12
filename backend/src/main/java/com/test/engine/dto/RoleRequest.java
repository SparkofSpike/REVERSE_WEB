package com.test.engine.dto;

import jakarta.validation.constraints.Pattern;
import lombok.Data;

/**
 * Target role for an admin-managed account. OP is not assignable: it is
 * configuration-driven only.
 */
@Data
public class RoleRequest {

    @Pattern(regexp = "USER|ADMIN", message = "角色只能是 USER 或 ADMIN")
    private String role;
}
