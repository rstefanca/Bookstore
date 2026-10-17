package cz.codingmonkey.bookstore.shared;

import jakarta.persistence.AttributeConverter;
import java.io.Serializable;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Types;
import java.util.function.LongFunction;
import org.hibernate.type.descriptor.WrapperOptions;
import org.hibernate.usertype.EnhancedUserType;
import org.jspecify.annotations.Nullable;

/**
 * Hibernate type that stores a {@link LongId} in a {@code BIGINT} column. Unlike a JPA {@code AttributeConverter},
 * it may be used on {@code @Id} attributes, including database-generated ones. Subclasses only supply the id class
 * and its constructor; use with {@code @Type(SubclassName.class)}.
 */
public abstract class LongIdType<T extends LongId> implements EnhancedUserType<T> {

    private final Class<T> idClass;
    private final LongFunction<T> wrap;

    protected LongIdType(Class<T> idClass, LongFunction<T> wrap) {
        this.idClass = idClass;
        this.wrap = wrap;
    }

    @Override
    public int getSqlType() {
        return Types.BIGINT;
    }

    @Override
    public Class<T> returnedClass() {
        return idClass;
    }

    @Override
    public @Nullable T nullSafeGet(ResultSet rs, int position, WrapperOptions options) throws SQLException {
        long value = rs.getLong(position);
        return rs.wasNull() ? null : wrap.apply(value);
    }

    @Override
    public void nullSafeSet(PreparedStatement st, @Nullable T value, int index, WrapperOptions options)
            throws SQLException {
        if (value == null) {
            st.setNull(index, Types.BIGINT);
        } else {
            st.setLong(index, value.value());
        }
    }

    @Override
    public @Nullable T deepCopy(@Nullable T value) {
        return value; // immutable
    }

    @Override
    public boolean isMutable() {
        return false;
    }

    @Override
    public @Nullable Serializable disassemble(@Nullable T value) {
        return value == null ? null : value.value();
    }

    @Override
    public @Nullable T assemble(@Nullable Serializable cached, Object owner) {
        return cached == null ? null : wrap.apply((Long) cached);
    }

    @Override
    public String toSqlLiteral(T value) {
        return Long.toString(value.value());
    }

    @Override
    public String toString(T value) {
        return Long.toString(value.value());
    }

    @Override
    public T fromStringValue(CharSequence value) {
        return wrap.apply(Long.parseLong(value.toString()));
    }

    /**
     * Lets Hibernate unwrap ids to {@code Long} where it binds them itself, e.g. as the array parameter of batch
     * fetches ({@code where book_id = any(?)} on PostgreSQL).
     */
    @Override
    public AttributeConverter<T, Long> getValueConverter() {
        return new AttributeConverter<>() {

            @Override
            public @Nullable Long convertToDatabaseColumn(@Nullable T id) {
                return id == null ? null : id.value();
            }

            @Override
            public @Nullable T convertToEntityAttribute(@Nullable Long value) {
                return value == null ? null : wrap.apply(value);
            }
        };
    }
}
