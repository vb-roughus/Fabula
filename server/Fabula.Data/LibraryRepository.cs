using Fabula.Core.Domain;
using Fabula.Core.Services;
using Microsoft.EntityFrameworkCore;

namespace Fabula.Data;

public class LibraryRepository(FabulaDbContext db) : ILibraryRepository
{
    public Task<LibraryFolder?> GetLibraryFolderAsync(int id, CancellationToken cancellationToken)
        => db.LibraryFolders.FirstOrDefaultAsync(f => f.Id == id, cancellationToken);

    public Task<Book?> FindBookByFolderAsync(int libraryFolderId, string bookDirectory, CancellationToken cancellationToken)
    {
        var prefix = bookDirectory.TrimEnd(Path.DirectorySeparatorChar, Path.AltDirectorySeparatorChar);
        return db.Books
            .Include(b => b.Files)
            .Include(b => b.Chapters)
            .Include(b => b.Authors)
            .Include(b => b.Narrators)
            .Include(b => b.Series)
            .Where(b => b.LibraryFolderId == libraryFolderId)
            .FirstOrDefaultAsync(b => b.Files.Any(f => f.Path.StartsWith(prefix)), cancellationToken);
    }

    public async Task UpsertBookAsync(
        Book book,
        string bookDirectory,
        IReadOnlyList<string> authorNames,
        IReadOnlyList<string> narratorNames,
        string? seriesName,
        decimal? seriesPosition,
        IReadOnlyList<AudioFile> files,
        IReadOnlyList<ChapterInfo> chapters,
        CancellationToken cancellationToken)
    {
        book.Authors = await ResolveAuthorsAsync(authorNames, cancellationToken);
        book.Narrators = await ResolveNarratorsAsync(narratorNames, cancellationToken);

        if (!string.IsNullOrWhiteSpace(seriesName))
        {
            book.Series = await ResolveSeriesAsync(seriesName, cancellationToken);
            book.SeriesPosition = seriesPosition;
        }
        else
        {
            book.Series = null;
            book.SeriesId = null;
            book.SeriesPosition = null;
        }

        if (book.Id == 0)
        {
            book.Files = files.ToList();
            book.Chapters = chapters.Select((c, i) => new Chapter
            {
                Index = i,
                Title = c.Title,
                Start = c.Start,
                End = c.End
            }).ToList();
            db.Books.Add(book);
        }
        else
        {
            db.AudioFiles.RemoveRange(book.Files);
            db.Chapters.RemoveRange(book.Chapters);
            book.Files = files.ToList();
            book.Chapters = chapters.Select((c, i) => new Chapter
            {
                Index = i,
                Title = c.Title,
                Start = c.Start,
                End = c.End
            }).ToList();
        }

        await db.SaveChangesAsync(cancellationToken);
    }

    public async Task<int> RemoveBooksWithMissingFilesAsync(int libraryFolderId, CancellationToken cancellationToken)
    {
        var books = await db.Books
            .Include(b => b.Files)
            .Where(b => b.LibraryFolderId == libraryFolderId)
            .ToListAsync(cancellationToken);

        var toRemove = books.Where(b => b.Files.Count == 0 || b.Files.Any(f => !File.Exists(f.Path))).ToList();
        if (toRemove.Count == 0) return 0;

        db.Books.RemoveRange(toRemove);
        await db.SaveChangesAsync(cancellationToken);
        return toRemove.Count;
    }

    public async Task MarkFolderScannedAsync(int libraryFolderId, DateTime scannedAt, CancellationToken cancellationToken)
    {
        var folder = await db.LibraryFolders.FindAsync([libraryFolderId], cancellationToken);
        if (folder is null) return;
        folder.LastScanAt = scannedAt;
        await db.SaveChangesAsync(cancellationToken);
    }

    private async Task<List<Author>> ResolveAuthorsAsync(IReadOnlyList<string> names, CancellationToken ct)
    {
        if (names.Count == 0) return [];
        var existing = await db.Authors.Where(a => names.Contains(a.Name)).ToListAsync(ct);
        var result = new List<Author>(names.Count);
        foreach (var name in names.Distinct())
        {
            var author = existing.FirstOrDefault(a => a.Name == name) ?? new Author { Name = name };
            if (author.Id == 0) db.Authors.Add(author);
            result.Add(author);
        }
        return result;
    }

    private async Task<List<Narrator>> ResolveNarratorsAsync(IReadOnlyList<string> names, CancellationToken ct)
    {
        if (names.Count == 0) return [];
        var existing = await db.Narrators.Where(n => names.Contains(n.Name)).ToListAsync(ct);
        var result = new List<Narrator>(names.Count);
        foreach (var name in names.Distinct())
        {
            var narrator = existing.FirstOrDefault(n => n.Name == name) ?? new Narrator { Name = name };
            if (narrator.Id == 0) db.Narrators.Add(narrator);
            result.Add(narrator);
        }
        return result;
    }

    private async Task<Series> ResolveSeriesAsync(string name, CancellationToken ct)
    {
        var series = await db.Series.FirstOrDefaultAsync(s => s.Name == name, ct);
        if (series is null)
        {
            series = new Series { Name = name };
            db.Series.Add(series);
        }
        return series;
    }
}
