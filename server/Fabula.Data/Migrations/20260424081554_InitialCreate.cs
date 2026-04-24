using System;
using Microsoft.EntityFrameworkCore.Migrations;

#nullable disable

namespace Fabula.Data.Migrations
{
    /// <inheritdoc />
    public partial class InitialCreate : Migration
    {
        /// <inheritdoc />
        protected override void Up(MigrationBuilder migrationBuilder)
        {
            migrationBuilder.CreateTable(
                name: "Authors",
                columns: table => new
                {
                    Id = table.Column<int>(type: "INTEGER", nullable: false)
                        .Annotation("Sqlite:Autoincrement", true),
                    Name = table.Column<string>(type: "TEXT", maxLength: 256, nullable: false),
                    SortName = table.Column<string>(type: "TEXT", maxLength: 256, nullable: true),
                    Description = table.Column<string>(type: "TEXT", nullable: true)
                },
                constraints: table =>
                {
                    table.PrimaryKey("PK_Authors", x => x.Id);
                });

            migrationBuilder.CreateTable(
                name: "LibraryFolders",
                columns: table => new
                {
                    Id = table.Column<int>(type: "INTEGER", nullable: false)
                        .Annotation("Sqlite:Autoincrement", true),
                    Name = table.Column<string>(type: "TEXT", maxLength: 128, nullable: false),
                    Path = table.Column<string>(type: "TEXT", maxLength: 1024, nullable: false),
                    LastScanAt = table.Column<DateTime>(type: "TEXT", nullable: true)
                },
                constraints: table =>
                {
                    table.PrimaryKey("PK_LibraryFolders", x => x.Id);
                });

            migrationBuilder.CreateTable(
                name: "Narrators",
                columns: table => new
                {
                    Id = table.Column<int>(type: "INTEGER", nullable: false)
                        .Annotation("Sqlite:Autoincrement", true),
                    Name = table.Column<string>(type: "TEXT", maxLength: 256, nullable: false)
                },
                constraints: table =>
                {
                    table.PrimaryKey("PK_Narrators", x => x.Id);
                });

            migrationBuilder.CreateTable(
                name: "Series",
                columns: table => new
                {
                    Id = table.Column<int>(type: "INTEGER", nullable: false)
                        .Annotation("Sqlite:Autoincrement", true),
                    Name = table.Column<string>(type: "TEXT", maxLength: 256, nullable: false),
                    Description = table.Column<string>(type: "TEXT", nullable: true)
                },
                constraints: table =>
                {
                    table.PrimaryKey("PK_Series", x => x.Id);
                });

            migrationBuilder.CreateTable(
                name: "Users",
                columns: table => new
                {
                    Id = table.Column<int>(type: "INTEGER", nullable: false)
                        .Annotation("Sqlite:Autoincrement", true),
                    Username = table.Column<string>(type: "TEXT", maxLength: 64, nullable: false),
                    PasswordHash = table.Column<string>(type: "TEXT", maxLength: 512, nullable: false),
                    IsAdmin = table.Column<bool>(type: "INTEGER", nullable: false),
                    CreatedAt = table.Column<DateTime>(type: "TEXT", nullable: false)
                },
                constraints: table =>
                {
                    table.PrimaryKey("PK_Users", x => x.Id);
                });

            migrationBuilder.CreateTable(
                name: "Books",
                columns: table => new
                {
                    Id = table.Column<int>(type: "INTEGER", nullable: false)
                        .Annotation("Sqlite:Autoincrement", true),
                    Title = table.Column<string>(type: "TEXT", maxLength: 512, nullable: false),
                    SortTitle = table.Column<string>(type: "TEXT", maxLength: 512, nullable: true),
                    Subtitle = table.Column<string>(type: "TEXT", maxLength: 512, nullable: true),
                    Description = table.Column<string>(type: "TEXT", nullable: true),
                    Language = table.Column<string>(type: "TEXT", maxLength: 16, nullable: true),
                    Publisher = table.Column<string>(type: "TEXT", maxLength: 256, nullable: true),
                    PublishYear = table.Column<int>(type: "INTEGER", nullable: true),
                    Isbn = table.Column<string>(type: "TEXT", maxLength: 32, nullable: true),
                    Asin = table.Column<string>(type: "TEXT", maxLength: 32, nullable: true),
                    SeriesId = table.Column<int>(type: "INTEGER", nullable: true),
                    SeriesPosition = table.Column<decimal>(type: "TEXT", precision: 6, scale: 2, nullable: true),
                    Duration = table.Column<TimeSpan>(type: "TEXT", nullable: false),
                    CoverPath = table.Column<string>(type: "TEXT", maxLength: 1024, nullable: true),
                    AddedAt = table.Column<DateTime>(type: "TEXT", nullable: false),
                    UpdatedAt = table.Column<DateTime>(type: "TEXT", nullable: false),
                    LibraryFolderId = table.Column<int>(type: "INTEGER", nullable: false)
                },
                constraints: table =>
                {
                    table.PrimaryKey("PK_Books", x => x.Id);
                    table.ForeignKey(
                        name: "FK_Books_LibraryFolders_LibraryFolderId",
                        column: x => x.LibraryFolderId,
                        principalTable: "LibraryFolders",
                        principalColumn: "Id",
                        onDelete: ReferentialAction.Restrict);
                    table.ForeignKey(
                        name: "FK_Books_Series_SeriesId",
                        column: x => x.SeriesId,
                        principalTable: "Series",
                        principalColumn: "Id",
                        onDelete: ReferentialAction.SetNull);
                });

            migrationBuilder.CreateTable(
                name: "AudioFiles",
                columns: table => new
                {
                    Id = table.Column<int>(type: "INTEGER", nullable: false)
                        .Annotation("Sqlite:Autoincrement", true),
                    BookId = table.Column<int>(type: "INTEGER", nullable: false),
                    TrackIndex = table.Column<int>(type: "INTEGER", nullable: false),
                    Path = table.Column<string>(type: "TEXT", maxLength: 1024, nullable: false),
                    SizeBytes = table.Column<long>(type: "INTEGER", nullable: false),
                    Duration = table.Column<TimeSpan>(type: "TEXT", nullable: false),
                    Codec = table.Column<string>(type: "TEXT", maxLength: 32, nullable: true),
                    BitrateKbps = table.Column<int>(type: "INTEGER", nullable: true),
                    SampleRate = table.Column<int>(type: "INTEGER", nullable: true),
                    OffsetInBook = table.Column<TimeSpan>(type: "TEXT", nullable: false)
                },
                constraints: table =>
                {
                    table.PrimaryKey("PK_AudioFiles", x => x.Id);
                    table.ForeignKey(
                        name: "FK_AudioFiles_Books_BookId",
                        column: x => x.BookId,
                        principalTable: "Books",
                        principalColumn: "Id",
                        onDelete: ReferentialAction.Cascade);
                });

            migrationBuilder.CreateTable(
                name: "AuthorBook",
                columns: table => new
                {
                    AuthorsId = table.Column<int>(type: "INTEGER", nullable: false),
                    BooksId = table.Column<int>(type: "INTEGER", nullable: false)
                },
                constraints: table =>
                {
                    table.PrimaryKey("PK_AuthorBook", x => new { x.AuthorsId, x.BooksId });
                    table.ForeignKey(
                        name: "FK_AuthorBook_Authors_AuthorsId",
                        column: x => x.AuthorsId,
                        principalTable: "Authors",
                        principalColumn: "Id",
                        onDelete: ReferentialAction.Cascade);
                    table.ForeignKey(
                        name: "FK_AuthorBook_Books_BooksId",
                        column: x => x.BooksId,
                        principalTable: "Books",
                        principalColumn: "Id",
                        onDelete: ReferentialAction.Cascade);
                });

            migrationBuilder.CreateTable(
                name: "BookNarrator",
                columns: table => new
                {
                    BooksId = table.Column<int>(type: "INTEGER", nullable: false),
                    NarratorsId = table.Column<int>(type: "INTEGER", nullable: false)
                },
                constraints: table =>
                {
                    table.PrimaryKey("PK_BookNarrator", x => new { x.BooksId, x.NarratorsId });
                    table.ForeignKey(
                        name: "FK_BookNarrator_Books_BooksId",
                        column: x => x.BooksId,
                        principalTable: "Books",
                        principalColumn: "Id",
                        onDelete: ReferentialAction.Cascade);
                    table.ForeignKey(
                        name: "FK_BookNarrator_Narrators_NarratorsId",
                        column: x => x.NarratorsId,
                        principalTable: "Narrators",
                        principalColumn: "Id",
                        onDelete: ReferentialAction.Cascade);
                });

            migrationBuilder.CreateTable(
                name: "Chapters",
                columns: table => new
                {
                    Id = table.Column<int>(type: "INTEGER", nullable: false)
                        .Annotation("Sqlite:Autoincrement", true),
                    BookId = table.Column<int>(type: "INTEGER", nullable: false),
                    Index = table.Column<int>(type: "INTEGER", nullable: false),
                    Title = table.Column<string>(type: "TEXT", maxLength: 512, nullable: false),
                    Start = table.Column<TimeSpan>(type: "TEXT", nullable: false),
                    End = table.Column<TimeSpan>(type: "TEXT", nullable: false)
                },
                constraints: table =>
                {
                    table.PrimaryKey("PK_Chapters", x => x.Id);
                    table.ForeignKey(
                        name: "FK_Chapters_Books_BookId",
                        column: x => x.BookId,
                        principalTable: "Books",
                        principalColumn: "Id",
                        onDelete: ReferentialAction.Cascade);
                });

            migrationBuilder.CreateTable(
                name: "PlaybackProgress",
                columns: table => new
                {
                    Id = table.Column<int>(type: "INTEGER", nullable: false)
                        .Annotation("Sqlite:Autoincrement", true),
                    UserId = table.Column<int>(type: "INTEGER", nullable: false),
                    BookId = table.Column<int>(type: "INTEGER", nullable: false),
                    Position = table.Column<TimeSpan>(type: "TEXT", nullable: false),
                    Finished = table.Column<bool>(type: "INTEGER", nullable: false),
                    UpdatedAt = table.Column<DateTime>(type: "TEXT", nullable: false),
                    LastDevice = table.Column<string>(type: "TEXT", maxLength: 128, nullable: true)
                },
                constraints: table =>
                {
                    table.PrimaryKey("PK_PlaybackProgress", x => x.Id);
                    table.ForeignKey(
                        name: "FK_PlaybackProgress_Books_BookId",
                        column: x => x.BookId,
                        principalTable: "Books",
                        principalColumn: "Id",
                        onDelete: ReferentialAction.Cascade);
                    table.ForeignKey(
                        name: "FK_PlaybackProgress_Users_UserId",
                        column: x => x.UserId,
                        principalTable: "Users",
                        principalColumn: "Id",
                        onDelete: ReferentialAction.Cascade);
                });

            migrationBuilder.CreateIndex(
                name: "IX_AudioFiles_BookId",
                table: "AudioFiles",
                column: "BookId");

            migrationBuilder.CreateIndex(
                name: "IX_AudioFiles_Path",
                table: "AudioFiles",
                column: "Path",
                unique: true);

            migrationBuilder.CreateIndex(
                name: "IX_AuthorBook_BooksId",
                table: "AuthorBook",
                column: "BooksId");

            migrationBuilder.CreateIndex(
                name: "IX_Authors_Name",
                table: "Authors",
                column: "Name");

            migrationBuilder.CreateIndex(
                name: "IX_BookNarrator_NarratorsId",
                table: "BookNarrator",
                column: "NarratorsId");

            migrationBuilder.CreateIndex(
                name: "IX_Books_LibraryFolderId",
                table: "Books",
                column: "LibraryFolderId");

            migrationBuilder.CreateIndex(
                name: "IX_Books_SeriesId_SeriesPosition",
                table: "Books",
                columns: new[] { "SeriesId", "SeriesPosition" });

            migrationBuilder.CreateIndex(
                name: "IX_Books_Title",
                table: "Books",
                column: "Title");

            migrationBuilder.CreateIndex(
                name: "IX_Chapters_BookId_Index",
                table: "Chapters",
                columns: new[] { "BookId", "Index" },
                unique: true);

            migrationBuilder.CreateIndex(
                name: "IX_LibraryFolders_Path",
                table: "LibraryFolders",
                column: "Path",
                unique: true);

            migrationBuilder.CreateIndex(
                name: "IX_Narrators_Name",
                table: "Narrators",
                column: "Name");

            migrationBuilder.CreateIndex(
                name: "IX_PlaybackProgress_BookId",
                table: "PlaybackProgress",
                column: "BookId");

            migrationBuilder.CreateIndex(
                name: "IX_PlaybackProgress_UserId_BookId",
                table: "PlaybackProgress",
                columns: new[] { "UserId", "BookId" },
                unique: true);

            migrationBuilder.CreateIndex(
                name: "IX_Series_Name",
                table: "Series",
                column: "Name",
                unique: true);

            migrationBuilder.CreateIndex(
                name: "IX_Users_Username",
                table: "Users",
                column: "Username",
                unique: true);
        }

        /// <inheritdoc />
        protected override void Down(MigrationBuilder migrationBuilder)
        {
            migrationBuilder.DropTable(
                name: "AudioFiles");

            migrationBuilder.DropTable(
                name: "AuthorBook");

            migrationBuilder.DropTable(
                name: "BookNarrator");

            migrationBuilder.DropTable(
                name: "Chapters");

            migrationBuilder.DropTable(
                name: "PlaybackProgress");

            migrationBuilder.DropTable(
                name: "Authors");

            migrationBuilder.DropTable(
                name: "Narrators");

            migrationBuilder.DropTable(
                name: "Books");

            migrationBuilder.DropTable(
                name: "Users");

            migrationBuilder.DropTable(
                name: "LibraryFolders");

            migrationBuilder.DropTable(
                name: "Series");
        }
    }
}
