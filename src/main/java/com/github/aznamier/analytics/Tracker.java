package com.github.aznamier.analytics;

import javax.validation.constraints.NotNull;
import java.util.Map;

public interface Tracker {
    void event(@NotNull String userId, @NotNull String eventName);

    void event(@NotNull String userId, @NotNull String eventName, Map<String, ?> eventProps);
}
