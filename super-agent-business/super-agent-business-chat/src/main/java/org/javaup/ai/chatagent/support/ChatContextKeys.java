package org.javaup.ai.chatagent.support;

public final class ChatContextKeys {

    public static final String EVENT_SINK = "chat.event.sink";
    public static final String EVENT_METADATA = "chat.event.metadata";
    public static final String REFERENCES = "chat.references";
    public static final String USED_TOOLS = "chat.used.tools";
    public static final String THINKING_STEPS = "chat.thinking.steps";
    public static final String QUESTION = "chat.question";
    public static final String CURRENT_DATE = "chat.current.date";
    public static final String CURRENT_DATE_TEXT = "chat.current.date.text";
    public static final String SELECTED_DOCUMENT_ID = "chat.selected.document.id";
    public static final String SELECTED_DOCUMENT_NAME = "chat.selected.document.name";

    private ChatContextKeys() {
    }
}
