using Microsoft.EntityFrameworkCore.Migrations;

#nullable disable

namespace Fabula.Data.Migrations
{
    /// <summary>
    /// Records which book fields an admin took over from an online catalogue,
    /// so the library scanner leaves them alone on a rescan. Nullable, no
    /// default: an existing book has nothing locked.
    /// </summary>
    public partial class AddMetadataLocks : Migration
    {
        /// <inheritdoc />
        protected override void Up(MigrationBuilder migrationBuilder)
        {
            migrationBuilder.AddColumn<string>(
                name: "MetadataLocks",
                table: "Books",
                type: "TEXT",
                nullable: true);
        }

        /// <inheritdoc />
        protected override void Down(MigrationBuilder migrationBuilder)
        {
            migrationBuilder.DropColumn(
                name: "MetadataLocks",
                table: "Books");
        }
    }
}
