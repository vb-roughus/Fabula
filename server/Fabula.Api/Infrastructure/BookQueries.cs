using Fabula.Core.Domain;

namespace Fabula.Api.Infrastructure;

/// <summary>
/// The pieces of the library listing that decide which books a page holds and
/// in what order. Pulled out of the endpoint so paging can be tested against a
/// real database: a page boundary is exactly where a filter or an ordering
/// that is slightly wrong shows up as a missing or repeated book.
/// </summary>
public static class BookQueries
{
    /// <summary>
    /// Keeps only books that belong to no series, when asked to. The libraries
    /// list these on their own and reach the rest through the series.
    /// </summary>
    public static IQueryable<Book> WhereStandalone(this IQueryable<Book> books, bool standalone) =>
        standalone ? books.Where(b => b.SeriesId == null) : books;

    /// <summary>
    /// Keeps only books of one library type -- audiobooks or radio plays.
    /// Done here rather than by the client: with a listing that arrives page by
    /// page, a client filtering what it has loaded would show "nothing here"
    /// for a type whose first book sits on page three.
    /// </summary>
    public static IQueryable<Book> WhereType(this IQueryable<Book> books, LibraryType? type) =>
        type is null ? books : books.Where(b => b.LibraryFolder.Type == type);

    /// <summary>
    /// The listing order: by title, then by id.
    ///
    /// The id is what makes paging safe. Two books with the same title have no
    /// defined order between them, and each page is a separate query -- so
    /// without a tie-break the same book can end one page and start the next,
    /// while another is never listed at all.
    /// </summary>
    public static IOrderedQueryable<Book> OrderForListing(this IQueryable<Book> books) =>
        books.OrderBy(b => b.SortTitle ?? b.Title).ThenBy(b => b.Id);
}
