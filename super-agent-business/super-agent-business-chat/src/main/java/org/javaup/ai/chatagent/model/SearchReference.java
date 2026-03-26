package org.javaup.ai.chatagent.model;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 引用来源信息。
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class SearchReference {

    private String title;
    private String url;
    private String snippet;
}
