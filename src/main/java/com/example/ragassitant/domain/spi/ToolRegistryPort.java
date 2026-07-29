package com.example.ragassitant.domain.spi;

import com.example.ragassitant.domain.model.ToolCall;

import java.util.List;
import java.util.Map;

public interface ToolRegistryPort {

    List<String> listToolNames();

    Map<String, String> getToolDescriptions();

    String invoke(ToolCall toolCall);

    String validateArguments(String toolName, Map<String, Object> arguments);
}