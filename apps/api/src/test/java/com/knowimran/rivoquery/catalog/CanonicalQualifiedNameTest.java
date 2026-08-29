package com.knowimran.rivoquery.catalog;

import com.knowimran.rivoquery.connection.DatabaseKind;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class CanonicalQualifiedNameTest {
    @Test
    void acceptsValidSimpleName() {
        CanonicalQualifiedName name = new CanonicalQualifiedName("sales", "orders");
        assertThat(name.schema()).isEqualTo("sales");
        assertThat(name.object()).isEqualTo("orders");
        assertThat(name.qualified()).isEqualTo("sales.orders");
    }

    @Test
    void normalizesCaseAndWhitespace() {
        CanonicalQualifiedName name = new CanonicalQualifiedName("  Sales  ", "  Orders  ");
        assertThat(name.schema()).isEqualTo("sales");
        assertThat(name.object()).isEqualTo("orders");
    }

    @Test
    void rejectsBlankParts() {
        assertThatThrownBy(() -> new CanonicalQualifiedName("", "orders"))
            .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new CanonicalQualifiedName("sales", ""))
            .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void rejectsNullParts() {
        assertThatThrownBy(() -> new CanonicalQualifiedName(null, "orders"))
            .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> new CanonicalQualifiedName("sales", null))
            .isInstanceOf(NullPointerException.class);
    }

    @Test
    void rejectsInvalidCharacters() {
        assertThatThrownBy(() -> new CanonicalQualifiedName("sales;drop", "orders"))
            .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new CanonicalQualifiedName("sales", "order;s"))
            .isInstanceOf(IllegalArgumentException.class);
    }
}
