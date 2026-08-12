package com.test.engine.dto;

import lombok.Data;

/**
 * Enable/disable flag for an admin-managed account.
 */
@Data
public class EnabledRequest {

    private boolean enabled;
}
