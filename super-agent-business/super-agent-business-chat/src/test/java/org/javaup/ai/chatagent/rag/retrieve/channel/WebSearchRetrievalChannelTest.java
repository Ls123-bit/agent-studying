package org.javaup.ai.chatagent.rag.retrieve.channel;

import org.javaup.ai.chatagent.config.TavilySearchProperties;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class WebSearchRetrievalChannelTest {

    @Test
    void shouldBuildStableIdsEvenWhenJavaHashCodeCollides() {
        WebSearchRetrievalChannel channel = new WebSearchRetrievalChannel(new TavilySearchProperties());

        String firstUrl = "https://example.com/Aa";
        String secondUrl = "https://example.com/BB";

        assertEquals(firstUrl.hashCode(), secondUrl.hashCode(), "测试前提失效：这两个 URL 需要形成 hashCode 碰撞");

        String firstId = channel.buildWebDocumentId(firstUrl);
        String secondId = channel.buildWebDocumentId(secondUrl);

        assertNotEquals(firstId, secondId);
        assertEquals(firstId, channel.buildWebDocumentId(firstUrl));
        assertTrue(firstId.startsWith("web-"));
        assertTrue(secondId.startsWith("web-"));
    }
}
