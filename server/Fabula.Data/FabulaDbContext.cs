using Fabula.Core.Domain;
using Microsoft.EntityFrameworkCore;

namespace Fabula.Data;

public class FabulaDbContext(DbContextOptions<FabulaDbContext> options) : DbContext(options)
{
    public DbSet<Book> Books => Set<Book>();
    public DbSet<Author> Authors => Set<Author>();
    public DbSet<Narrator> Narrators => Set<Narrator>();
    public DbSet<Series> Series => Set<Series>();
    public DbSet<Chapter> Chapters => Set<Chapter>();
    public DbSet<AudioFile> AudioFiles => Set<AudioFile>();
    public DbSet<LibraryFolder> LibraryFolders => Set<LibraryFolder>();
    public DbSet<User> Users => Set<User>();
    public DbSet<PlaybackProgress> PlaybackProgress => Set<PlaybackProgress>();

    protected override void OnModelCreating(ModelBuilder b)
    {
        b.Entity<Author>(e =>
        {
            e.HasIndex(x => x.Name);
            e.Property(x => x.Name).HasMaxLength(256).IsRequired();
            e.Property(x => x.SortName).HasMaxLength(256);
        });

        b.Entity<Narrator>(e =>
        {
            e.HasIndex(x => x.Name);
            e.Property(x => x.Name).HasMaxLength(256).IsRequired();
        });

        b.Entity<Series>(e =>
        {
            e.HasIndex(x => x.Name).IsUnique();
            e.Property(x => x.Name).HasMaxLength(256).IsRequired();
        });

        b.Entity<LibraryFolder>(e =>
        {
            e.HasIndex(x => x.Path).IsUnique();
            e.Property(x => x.Name).HasMaxLength(128).IsRequired();
            e.Property(x => x.Path).HasMaxLength(1024).IsRequired();
        });

        b.Entity<Book>(e =>
        {
            e.HasIndex(x => x.Title);
            e.HasIndex(x => new { x.SeriesId, x.SeriesPosition });
            e.Property(x => x.Title).HasMaxLength(512).IsRequired();
            e.Property(x => x.SortTitle).HasMaxLength(512);
            e.Property(x => x.Subtitle).HasMaxLength(512);
            e.Property(x => x.Language).HasMaxLength(16);
            e.Property(x => x.Publisher).HasMaxLength(256);
            e.Property(x => x.Isbn).HasMaxLength(32);
            e.Property(x => x.Asin).HasMaxLength(32);
            e.Property(x => x.CoverPath).HasMaxLength(1024);
            e.Property(x => x.SeriesPosition).HasPrecision(6, 2);

            e.HasOne(x => x.LibraryFolder)
                .WithMany(f => f.Books)
                .HasForeignKey(x => x.LibraryFolderId)
                .OnDelete(DeleteBehavior.Restrict);

            e.HasOne(x => x.Series)
                .WithMany(s => s.Books)
                .HasForeignKey(x => x.SeriesId)
                .OnDelete(DeleteBehavior.SetNull);

            e.HasMany(x => x.Authors).WithMany(a => a.Books);
            e.HasMany(x => x.Narrators).WithMany(n => n.Books);
        });

        b.Entity<AudioFile>(e =>
        {
            e.HasIndex(x => x.Path).IsUnique();
            e.Property(x => x.Path).HasMaxLength(1024).IsRequired();
            e.Property(x => x.Codec).HasMaxLength(32);

            e.HasOne(x => x.Book)
                .WithMany(b => b.Files)
                .HasForeignKey(x => x.BookId)
                .OnDelete(DeleteBehavior.Cascade);
        });

        b.Entity<Chapter>(e =>
        {
            e.HasIndex(x => new { x.BookId, x.Index }).IsUnique();
            e.Property(x => x.Title).HasMaxLength(512).IsRequired();

            e.HasOne(x => x.Book)
                .WithMany(b => b.Chapters)
                .HasForeignKey(x => x.BookId)
                .OnDelete(DeleteBehavior.Cascade);
        });

        b.Entity<User>(e =>
        {
            e.HasIndex(x => x.Username).IsUnique();
            e.Property(x => x.Username).HasMaxLength(64).IsRequired();
            e.Property(x => x.PasswordHash).HasMaxLength(512).IsRequired();
        });

        b.Entity<PlaybackProgress>(e =>
        {
            e.HasIndex(x => new { x.UserId, x.BookId }).IsUnique();
            e.Property(x => x.LastDevice).HasMaxLength(128);

            e.HasOne(x => x.User)
                .WithMany(u => u.Progress)
                .HasForeignKey(x => x.UserId)
                .OnDelete(DeleteBehavior.Cascade);

            e.HasOne(x => x.Book)
                .WithMany()
                .HasForeignKey(x => x.BookId)
                .OnDelete(DeleteBehavior.Cascade);
        });
    }
}
