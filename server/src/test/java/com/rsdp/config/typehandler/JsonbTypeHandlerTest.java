package com.rsdp.config.typehandler;

import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.postgresql.util.PGobject;

import java.lang.reflect.Field;
import java.sql.PreparedStatement;
import java.sql.Types;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

/**
 * {@link JsonbTypeHandler} 单元测试。
 */
class JsonbTypeHandlerTest {

    /** 用于构造带泛型字段（{@code List<String>}）的 TypeHandler。 */
    @SuppressWarnings("unused")
    private static class Holder {
        private List<String> tags;
    }

    @Test
    void setParameter_shouldSetNullWithTypeOtherWhenParameterIsNull() throws Exception {
        JsonbTypeHandler handler = new JsonbTypeHandler(String.class);
        PreparedStatement ps = mock(PreparedStatement.class);

        handler.setParameter(ps, 1, null, null);

        verify(ps).setNull(1, Types.OTHER);
    }

    @Test
    void setParameter_shouldWriteJsonbPgObjectWhenParameterIsString() throws Exception {
        JsonbTypeHandler handler = new JsonbTypeHandler(String.class);
        PreparedStatement ps = mock(PreparedStatement.class);

        handler.setParameter(ps, 2, "{\"a\":1}", null);

        ArgumentCaptor<PGobject> captor = ArgumentCaptor.forClass(PGobject.class);
        verify(ps).setObject(eq(2), captor.capture());
        assertThat(captor.getValue().getType()).isEqualTo("jsonb");
        assertThat(captor.getValue().getValue()).isEqualTo("{\"a\":1}");
    }

    @Test
    void setParameter_shouldSerializeNonStringParameterToJson() throws Exception {
        JsonbTypeHandler handler = new JsonbTypeHandler(Object.class);
        PreparedStatement ps = mock(PreparedStatement.class);

        handler.setParameter(ps, 1, List.of("a", "b"), null);

        ArgumentCaptor<PGobject> captor = ArgumentCaptor.forClass(PGobject.class);
        verify(ps).setObject(eq(1), captor.capture());
        assertThat(captor.getValue().getType()).isEqualTo("jsonb");
        assertThat(captor.getValue().getValue()).isEqualTo("[\"a\",\"b\"]");
    }

    @Test
    void parse_shouldReturnRawStringWhenFieldTypeIsString() {
        JsonbTypeHandler handler = new JsonbTypeHandler(String.class);

        assertThat(handler.parse("{\"a\":1}")).isEqualTo("{\"a\":1}");
    }

    @Test
    void parse_shouldDeserializeWhenFieldTypeIsGenericList() throws Exception {
        Field field = Holder.class.getDeclaredField("tags");
        JsonbTypeHandler handler = new JsonbTypeHandler(Object.class, field);

        Object result = handler.parse("[\"a\",\"b\"]");

        assertThat(result).isEqualTo(List.of("a", "b"));
    }
}
