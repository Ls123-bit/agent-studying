package org.javaup.ai.chatagent.model;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 通用动作型接口返回值。
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class ActionResponse {

    private boolean success;
    private String message;
}
