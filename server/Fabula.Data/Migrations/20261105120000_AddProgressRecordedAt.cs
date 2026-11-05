using System;
using Microsoft.EntityFrameworkCore.Migrations;

#nullable disable

namespace Fabula.Data.Migrations
{
    /// <summary>
    /// Keeps when a device recorded a position, so progress is ordered by
    /// listening time rather than by arrival. Nullable, no default: existing
    /// rows fall back to the time they arrived.
    /// </summary>
    public partial class AddProgressRecordedAt : Migration
    {
        /// <inheritdoc />
        protected override void Up(MigrationBuilder migrationBuilder)
        {
            migrationBuilder.AddColumn<DateTime>(
                name: "RecordedAt",
                table: "PlaybackProgress",
                type: "TEXT",
                nullable: true);
        }

        /// <inheritdoc />
        protected override void Down(MigrationBuilder migrationBuilder)
        {
            migrationBuilder.DropColumn(
                name: "RecordedAt",
                table: "PlaybackProgress");
        }
    }
}
