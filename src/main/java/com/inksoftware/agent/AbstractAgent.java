package com.inksoftware.agent;

import java.util.ArrayDeque;
import java.util.Deque;

//Abstract base class for agents that provides chat history functionality
public abstract class AbstractAgent implements Agent {
    private final String name;
    private final Deque<String> chatHistoryContext;

    protected AbstractAgent(String name) {
        this.name = name;
        this.chatHistoryContext = new ArrayDeque<>();
    }

    @Override
    public String getName() {
        return name;
    }

    //returns the current chat history context
    public Deque<String> getChatHistoryContext() {
        return chatHistoryContext;
    }

    //adds a message to the chat history and trims it if necessary
    public void addToChatHistory(String message) {
        chatHistoryContext.addLast(message);
        trimChatHistoryContext();
    }

    //trims the chat history context to the maximum allowed messages
    public Deque<String> trimChatHistoryContext() {
        int maxMessages = getMaxContextMessages();
        while (chatHistoryContext.size() > maxMessages) {
            chatHistoryContext.removeFirst(); //oldest message is first element since FIFO
        }
        return chatHistoryContext;
    }

    //Subclasses must implement this to define their maximum context message count
    //it allows to define a custom amount for each agent via macro
    protected abstract int getMaxContextMessages();
}
