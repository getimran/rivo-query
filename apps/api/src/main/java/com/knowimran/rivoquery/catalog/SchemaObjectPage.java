package com.knowimran.rivoquery.catalog;

import java.util.Collections;
import java.util.List;
import java.util.Objects;

public record SchemaObjectPage(
    List<SchemaObject> items,
    String nextCursor
) {
    private static final int MAX_ITEMS = 100;

    public SchemaObjectPage {
        if (items == null) {
            items = Collections.emptyList();
        }
        if (items.size() > MAX_ITEMS) {
            throw new IllegalArgumentException("items exceed maximum of " + MAX_ITEMS);
        }
        items = Collections.unmodifiableList(new java.util.ArrayList<>(items));
        if (nextCursor != null && nextCursor.isBlank()) {
            nextCursor = null;
        }
    }
}
