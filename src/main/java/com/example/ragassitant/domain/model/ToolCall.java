package com.example.ragassitant.domain.model;

import java.util.Map;

public class ToolCall {

    public final String name;
    public final Map<String, Object> arguments;

    public ToolCall(String name, Map<String, Object> arguments) {
        this.name = name;
        this.arguments = arguments;
    }
}