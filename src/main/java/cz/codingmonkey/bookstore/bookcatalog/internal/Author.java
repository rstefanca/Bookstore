package cz.codingmonkey.bookstore.bookcatalog.internal;

import cz.codingmonkey.bookstore.bookcatalog.AuthorId;
import cz.codingmonkey.bookstore.bookcatalog.AuthorIdType;
import cz.codingmonkey.bookstore.bookcatalog.GenreId;
import cz.codingmonkey.bookstore.bookcatalog.GenreIdType;
import jakarta.persistence.CollectionTable;
import jakarta.persistence.Column;
import jakarta.persistence.ElementCollection;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import java.util.HashSet;
import java.util.Set;
import org.hibernate.annotations.BatchSize;
import org.hibernate.annotations.Type;

@Entity
public class Author {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Type(AuthorIdType.class)
    private AuthorId id;

    @Column(nullable = false)
    private String name;

    /** Unordered; references the {@link Genre} aggregate by id only. */
    @ElementCollection
    @CollectionTable(name = "author_favorite_genre", joinColumns = @JoinColumn(name = "author_id"))
    @Column(name = "genre_id", nullable = false)
    @Type(GenreIdType.class)
    @BatchSize(size = 50)
    private Set<GenreId> favoriteGenreIds = new HashSet<>();

    protected Author() {
    }

    public Author(String name) {
        this(name, Set.of());
    }

    public Author(String name, Set<GenreId> favoriteGenreIds) {
        update(name, favoriteGenreIds);
    }

    public AuthorId getId() { return id; }
    public String getName() { return name; }
    public Set<GenreId> getFavoriteGenreIds() { return Set.copyOf(favoriteGenreIds); }

    public void update(String name, Set<GenreId> favoriteGenreIds) {
        this.name = name;
        this.favoriteGenreIds.retainAll(favoriteGenreIds);
        this.favoriteGenreIds.addAll(favoriteGenreIds);
    }
}
