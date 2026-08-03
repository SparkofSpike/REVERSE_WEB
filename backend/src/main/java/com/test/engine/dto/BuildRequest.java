package com.test.engine.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Size;
import lombok.Data;

import java.util.List;

@Data
public class BuildRequest {

    @NotBlank(message = "构筑名称不能为空")
    @Size(max = 64, message = "构筑名称过长")
    private String name;

    @NotBlank(message = "卡包不能为空")
    private String packId;

    @NotEmpty(message = "至少部署一个角色")
    @Size(min = 1, max = 4, message = "角色数量需在 1-4 之间")
    private List<String> characterIds;

    private String initialPerkId;
}
