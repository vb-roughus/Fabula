using Fabula.Core.Domain;
using Fabula.Core.Services;
using Fabula.Data;
using Microsoft.Data.Sqlite;
using Microsoft.EntityFrameworkCore;
using Microsoft.Extensions.Logging.Abstractions;
using Xunit;

namespace Fabula.Tests;

/// <summary>
/// Taking catalogue metadata over, and a rescan afterwards -- against a real
/// SQLite database and the real scanner, with only the tag reader and the
/// cover store replaced.
///
/// The rescan is the point: a field that was taken over and then quietly
/// overwritten by the next scan is a correction that did not stick.
/// </summary>
public class MetadataApplyTests : IDisposable
{
    private readonly SqliteConnection _connection;
    private readonly FabulaDbContext _db;
    private readonly LibraryRepository _repository;
    private readonly string _root;
    private readonly string _bookDir;
    private readonly string _audioFile;
    private readonly LibraryFolder _folder;
    private readonly FakeTagReader _tags = new();
    private readonly FakeCoverStore _covers = new();
    private readonly LibraryScanner _scanner;

    public MetadataApplyTests()
    {
        _connection = new SqliteConnection("Filename=:memory:");
        _connection.Open();
        _db = new FabulaDbContext(new DbContextOptionsBuilder<FabulaDbContext>()
            .UseSqlite(_connection)
            .Options);
        _db.Database.EnsureCreated();
        _repository = new LibraryRepository(_db);

        _root = Path.Combine(Path.GetTempPath(), "fabula-tests-" + Guid.NewGuid().ToString("N"));
        _bookDir = Path.Combine(_root, "Momo");
        Directory.CreateDirectory(_bookDir);
        _audioFile = Path.Combine(_bookDir, "01.mp3");
        File.WriteAllBytes(_audioFile, new byte[100]);

        _folder = new LibraryFolder { Name = "Hörbücher", Path = _root };
        _db.LibraryFolders.Add(_folder);
        _db.SaveChanges();

        _scanner = new LibraryScanner(_repository, _tags, _covers, NullLogger<LibraryScanner>.Instance);
    }

    public void Dispose()
    {
        _db.Dispose();
        _connection.Dispose();
        try { Directory.Delete(_root, recursive: true); } catch (IOException) { }
        GC.SuppressFinalize(this);
    }

    private sealed class FakeTagReader : IAudioMetadataReader
    {
        public string Album { get; set; } = "Momo";
        public string Publisher { get; set; } = "Tag-Verlag";
        public string Narrator { get; set; } = "Tag Sprecher";
        public string Description { get; set; } = "Aus den Tags";

        public AudioMetadata Read(string filePath) => new(
            Title: "Track 1",
            Album: Album,
            Subtitle: null,
            Authors: ["Michael Ende"],
            Narrators: [Narrator],
            SeriesName: null,
            SeriesPosition: null,
            Description: Description,
            Language: "de",
            Publisher: Publisher,
            Year: 1999,
            Isbn: null,
            Asin: null,
            Duration: TimeSpan.FromMinutes(420),
            Codec: "mp3",
            BitrateKbps: 64,
            SampleRate: 44100,
            CoverImage: null,
            CoverMimeType: null,
            Chapters: []);
    }

    private sealed class FakeCoverStore : ICoverStore
    {
        public List<string> Saved { get; } = [];

        public Task<string> SaveCoverAsync(string bookKey, byte[] data, string? mimeType, CancellationToken ct)
        {
            var path = $"saved-{Saved.Count + 1}.jpg";
            Saved.Add(path);
            return Task.FromResult(path);
        }

        public string GetCoverFilePath(string relativeCoverPath) => relativeCoverPath;
    }

    private async Task Scan() => await _scanner.ScanAsync(_folder.Id);

    /// <summary>Makes the scanner see changed audio, so it reads the tags again.</summary>
    private void ChangeTheAudio() => File.AppendAllText(_audioFile, "more");

    private Book Load()
    {
        _db.ChangeTracker.Clear();
        return _db.Books
            .AsSplitQuery()
            .Include(b => b.Authors)
            .Include(b => b.Narrators)
            .Include(b => b.Series)
            .Single();
    }

    private static BookMetadataUpdate Update(params string[] fields) => new()
    {
        Fields = fields.ToHashSet(),
        Title = "Momo oder Die seltsame Geschichte",
        Description = "Aus dem Katalog",
        Authors = ["Michael Ende"],
        Narrators = ["Gert Westphal"],
        SeriesName = "Michael-Ende-Bibliothek",
        SeriesPosition = 3m,
        Publisher = "Der Hörverlag",
        CoverPath = "catalog.jpg"
    };

    // --- applying ---------------------------------------------------------------

    [Fact]
    public async Task Writes_and_locks_the_chosen_fields()
    {
        await Scan();
        var id = Load().Id;

        var taken = await _repository.ApplyMetadataAsync(id, Update(MetadataLocks.Narrators, MetadataLocks.Description), default);

        var book = Load();
        Assert.Equal(new[] { "description", "narrators" }, taken!.OrderBy(t => t));
        Assert.Equal(new[] { "Gert Westphal" }, book.Narrators.Select(n => n.Name));
        Assert.Equal("Aus dem Katalog", book.Description);
        Assert.Equal("Momo", book.Title);
        Assert.Equal("narrators,description", book.MetadataLocks);
    }

    /// <summary>An author already in the library is reused, not created twice.</summary>
    [Fact]
    public async Task Reuses_people_already_in_the_library()
    {
        await Scan();
        var id = Load().Id;

        await _repository.ApplyMetadataAsync(id, Update(MetadataLocks.Authors), default);

        Assert.Equal(1, _db.Authors.Count(a => a.Name == "Michael Ende"));
    }

    [Fact]
    public async Task Puts_the_book_into_the_catalogue_series_at_its_volume()
    {
        await Scan();
        var id = Load().Id;

        await _repository.ApplyMetadataAsync(id, Update(MetadataLocks.Series), default);

        var book = Load();
        Assert.Equal("Michael-Ende-Bibliothek", book.Series!.Name);
        Assert.Equal(3m, book.SeriesPosition);
        Assert.True(book.SeriesPositionManuallySet);
    }

    [Fact]
    public async Task An_unknown_book_is_reported_as_such()
    {
        Assert.Null(await _repository.ApplyMetadataAsync(4711, Update(MetadataLocks.Title), default));
    }

    // --- rescanning -------------------------------------------------------------

    /// <summary>
    /// The control case: without a lock, changed audio means the tags are read
    /// again and win. Without it, the next test could pass for the wrong reason.
    /// </summary>
    [Fact]
    public async Task Without_a_lock_a_rescan_takes_the_tags_again()
    {
        await Scan();
        _tags.Publisher = "Neuer Verlag";

        ChangeTheAudio();
        await Scan();

        Assert.Equal("Neuer Verlag", Load().Publisher);
    }

    [Fact]
    public async Task Locked_fields_survive_a_rescan_and_the_rest_follows_the_tags()
    {
        await Scan();
        var id = Load().Id;
        await _repository.ApplyMetadataAsync(
            id, Update(MetadataLocks.Title, MetadataLocks.Narrators, MetadataLocks.Series), default);

        _tags.Album = "Momo (aus den Tags)";
        _tags.Narrator = "Anderer Sprecher";
        _tags.Publisher = "Neuer Verlag";
        ChangeTheAudio();
        await Scan();

        var book = Load();
        Assert.Equal("Momo oder Die seltsame Geschichte", book.Title);
        Assert.Equal(new[] { "Gert Westphal" }, book.Narrators.Select(n => n.Name));
        Assert.Equal("Michael-Ende-Bibliothek", book.Series!.Name);
        Assert.Equal(3m, book.SeriesPosition);
        Assert.Equal("Neuer Verlag", book.Publisher);
    }

    [Fact]
    public async Task A_locked_cover_survives_a_rescan_of_changed_audio()
    {
        await Scan();
        var id = Load().Id;
        await _repository.ApplyMetadataAsync(id, Update(MetadataLocks.Cover), default);

        File.WriteAllBytes(Path.Combine(_bookDir, "cover.jpg"), new byte[10]);
        ChangeTheAudio();
        await Scan();

        Assert.Equal("catalog.jpg", Load().CoverPath);
    }

    /// <summary>
    /// The quieter path: audio unchanged, but a cover image dropped into the
    /// folder. That one used to replace any cover on its own.
    /// </summary>
    [Fact]
    public async Task A_locked_cover_is_not_replaced_by_a_folder_image()
    {
        await Scan();
        var id = Load().Id;
        await _repository.ApplyMetadataAsync(id, Update(MetadataLocks.Cover), default);

        var image = Path.Combine(_bookDir, "cover.jpg");
        File.WriteAllBytes(image, new byte[10]);
        File.SetLastWriteTimeUtc(image, DateTime.UtcNow.AddMinutes(5));
        await Scan();

        Assert.Equal("catalog.jpg", Load().CoverPath);
    }

    [Fact]
    public async Task Without_a_lock_a_folder_image_still_becomes_the_cover()
    {
        await Scan();

        var image = Path.Combine(_bookDir, "cover.jpg");
        File.WriteAllBytes(image, new byte[10]);
        File.SetLastWriteTimeUtc(image, DateTime.UtcNow.AddMinutes(5));
        await Scan();

        Assert.StartsWith("saved-", Load().CoverPath);
    }
}
