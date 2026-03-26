package org.javaup.database.dto;


import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.media.Schema.RequiredMode;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

/**
 * 基础分页查询入参。
 *
 * <p>给需要分页查询的接口复用，避免每个 DTO 都重复声明页码和页大小。</p>
 */
@Data
public class BasePageDto {

    /**
     * 页码，从 1 开始。
     */
    @Schema(name ="pageNumber", type ="Long", description ="页码",requiredMode= RequiredMode.REQUIRED)
    @NotNull
    private Integer pageNumber;

    /**
     * 每页数量。
     */
    @Schema(name ="pageSize", type ="Long", description ="页大小",requiredMode= RequiredMode.REQUIRED)
    @NotNull
    private Integer pageSize;
}
