namespace Fabula.Core.Services;

/// <summary>
/// Sorts strings by interleaving numeric runs (compared by value) with text
/// runs (compared case-insensitively), so that "track2.mp3" comes before
/// "track10.mp3" -- the way Windows Explorer presents files.
/// </summary>
public sealed class NaturalStringComparer : IComparer<string>
{
    public static readonly NaturalStringComparer Instance = new();

    public int Compare(string? x, string? y)
    {
        if (x is null) return y is null ? 0 : -1;
        if (y is null) return 1;

        int ix = 0, iy = 0;
        while (ix < x.Length && iy < y.Length)
        {
            var dx = char.IsDigit(x[ix]);
            var dy = char.IsDigit(y[iy]);

            if (dx && dy)
            {
                // Find the end of each numeric run.
                int startX = ix;
                while (ix < x.Length && char.IsDigit(x[ix])) ix++;
                int startY = iy;
                while (iy < y.Length && char.IsDigit(y[iy])) iy++;

                // Skip leading zeros, then compare by length (longer = larger),
                // then digit-by-digit. This handles arbitrarily large numbers
                // without parsing into a fixed-size integer.
                int trimmedStartX = startX;
                while (trimmedStartX < ix - 1 && x[trimmedStartX] == '0') trimmedStartX++;
                int trimmedStartY = startY;
                while (trimmedStartY < iy - 1 && y[trimmedStartY] == '0') trimmedStartY++;

                int lenX = ix - trimmedStartX;
                int lenY = iy - trimmedStartY;
                if (lenX != lenY) return lenX - lenY;

                for (int k = 0; k < lenX; k++)
                {
                    var diff = x[trimmedStartX + k] - y[trimmedStartY + k];
                    if (diff != 0) return diff;
                }

                // Numerically equal -- shorter original (more leading zeros) wins
                // by ordinal so the comparer is stable for equal numbers.
                var rawDiff = (ix - startX) - (iy - startY);
                if (rawDiff != 0) return rawDiff;
            }
            else if (dx != dy)
            {
                // Digits sort before letters when the runs differ in kind.
                return dx ? -1 : 1;
            }
            else
            {
                var diff = char.ToLowerInvariant(x[ix]).CompareTo(char.ToLowerInvariant(y[iy]));
                if (diff != 0) return diff;
                ix++;
                iy++;
            }
        }

        return (x.Length - ix) - (y.Length - iy);
    }
}
