package cz.codingmonkey.bookstore.bookcatalog.internal;

import cz.codingmonkey.bookstore.bookcatalog.GenreId;
import cz.codingmonkey.bookstore.bookcatalog.GenreIdType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import org.hibernate.annotations.Type;

@Entity
public class Genre {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Type(GenreIdType.class)
    private GenreId id;

    /** Unique regardless of case. */
    @Column(nullable = false, length = 100)
    private String name;

    protected Genre() {
    }

    public Genre(String name) {
        this.name = name.strip();
    }

    public GenreId getId() { return id; }
    public String getName() { return name; }

    public void rename(String name) {
        this.name = name.strip();
    }
}
