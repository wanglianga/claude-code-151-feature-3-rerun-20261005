package com.example.library.web.dto;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

/** 新增复本到馆登记：为某书目在指定分馆登记 count 册新复本并立即参与队列平衡分配 */
public record AcquireCopiesRequest(
        @NotNull Long bibId,
        @NotBlank String libraryCode,
        @NotNull @Min(1) Integer count,
        @NotBlank String staff,
        String note
) {}
