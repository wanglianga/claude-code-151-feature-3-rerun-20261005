package com.example.library.web.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

/** 馆员人工改配：把某复本改配给指定排队预约，调整原因必填并进入馆藏流转记录 */
public record ManualAdjustRequest(
        @NotBlank String barcode,
        @NotNull Long targetHoldId,
        @NotBlank String staff,
        @NotBlank String reason
) {}
