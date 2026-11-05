using Fabula.Api.Infrastructure.Metadata;
using Fabula.Core.Services;
using Xunit;

namespace Fabula.Tests;

/// <summary>What the clients call each field, and the list of locked ones they show.</summary>
public class MetadataLabelsTests
{
    [Fact]
    public void Every_field_has_a_label_of_its_own()
    {
        var labels = MetadataLocks.All.Select(MetadataLabels.For).ToList();

        Assert.DoesNotContain(labels, label => MetadataLocks.All.Contains(label));
        Assert.Equal(labels.Count, labels.Distinct().Count());
    }

    [Fact]
    public void Locked_fields_come_labelled_and_in_display_order()
    {
        var locked = MetadataLabels.Locked("cover,title,narrators");

        Assert.Equal(new[] { "title", "narrators", "cover" }, locked.Select(l => l.Key));
        Assert.Equal(new[] { "Titel", "Sprecher", "Cover" }, locked.Select(l => l.Label));
    }

    [Fact]
    public void Nothing_locked_is_an_empty_list()
    {
        Assert.Empty(MetadataLabels.Locked(null));
    }
}
