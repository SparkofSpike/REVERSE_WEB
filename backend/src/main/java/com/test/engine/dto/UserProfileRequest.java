package com.test.engine.dto;

import jakarta.validation.constraints.Size;
import lombok.Data;

@Data
public class UserProfileRequest {

    /** Display name; blank clears it back to null. */
    @Size(max = 32, message = "昵称长度需在 1-32 之间")
    private String nickname;
}
