package cz.codingmonkey.bookstore.bookcatalog.internal;

import cz.codingmonkey.bookstore.bookcatalog.AuthorId;
import cz.codingmonkey.bookstore.bookcatalog.AuthorIdType;
import cz.codingmonkey.bookstore.bookcatalog.BookId;
import cz.codingmonkey.bookstore.bookcatalog.BookIdType;
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
import jakarta.persistence.OrderColumn;
import jakarta.persistence.Version;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.hibernate.annotations.BatchSize;
import org.hibernate.annotations.Type;
import org.jspecify.annotations.Nullable;

@Entity
public class Book {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Type(BookIdType.class)
    private BookId id;

    @Column(nullable = false)
    private String title;

    /** Authors in credited order; references the {@link Author} aggregate by id only. */
    @ElementCollection
    @CollectionTable(name = "book_author", joinColumns = @JoinColumn(name = "book_id"))
    @OrderColumn(name = "position")
    @Column(name = "author_id", nullable = false)
    @Type(AuthorIdType.class)
    @BatchSize(size = 50)
    private List<AuthorId> authorIds = new ArrayList<>();

    /** Unordered; references the {@link Genre} aggregate by id only. */
    @ElementCollection
    @CollectionTable(name = "book_genre", joinColumns = @JoinColumn(name = "book_id"))
    @Column(name = "genre_id", nullable = false)
    @Type(GenreIdType.class)
    @BatchSize(size = 50)
    private Set<GenreId> genreIds = new HashSet<>();

    @Column(nullable = false, unique = true)
    private String isbn;

    private @Nullable Integer publishedYear;

    @Column(length = 2000)
    private @Nullable String description;

    @Version
    private long version;

    protected Book() {
    }

    public Book(String title, List<AuthorId> authorIds, Set<GenreId> genreIds, String isbn,
            @Nullable Integer publishedYear, @Nullable String description) {
        update(title, authorIds, genreIds, isbn, publishedYear, description);
    }

    public BookId getId() { return id; }
    public String getTitle() { return title; }
    public List<AuthorId> getAuthorIds() { return List.copyOf(authorIds); }
    public Set<GenreId> getGenreIds() { return Set.copyOf(genreIds); }
    public String getIsbn() { return isbn; }
    public @Nullable Integer getPublishedYear() { return publishedYear; }
    public @Nullable String getDescription() { return description; }
    public long getVersion() { return version; }

    public void update(String title, List<AuthorId> authorIds, Set<GenreId> genreIds, String isbn,
            @Nullable Integer publishedYear, @Nullable String description) {
        if (authorIds.isEmpty()) {
            throw new IllegalArgumentException("A book needs at least one author");
        }
        this.title = title;
        this.authorIds.clear();
        this.authorIds.addAll(authorIds);
        this.genreIds.retainAll(genreIds);
        this.genreIds.addAll(genreIds);
        this.isbn = isbn;
        this.publishedYear = publishedYear;
        this.description = description;
    }
}
