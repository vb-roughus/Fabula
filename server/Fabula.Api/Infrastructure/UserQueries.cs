using Fabula.Core.Domain;
using Microsoft.EntityFrameworkCore;

namespace Fabula.Api.Infrastructure;

public static class UserQueries
{
    /// <summary>
    /// Matches a username without regard to case.
    ///
    /// SQLite compares TEXT case-sensitively by default, so "Rolf" did not find
    /// the account called "rolf" -- and an Android keyboard capitalises the
    /// first letter of a fresh field by default. The result was a login that
    /// refused correct credentials, for a reason invisible from either side.
    ///
    /// NOCASE is SQLite's built-in collation, applied here at the comparison
    /// rather than on the column: no schema change, and therefore no migration.
    /// Its one limit is that it folds ASCII only -- "Ötzi" and "ötzi" stay
    /// distinct. Accepted knowingly; the alternative is a custom collation the
    /// database would have to be rebuilt for.
    ///
    /// Shared by the login lookup and the duplicate check on purpose. Matching
    /// loosely while checking uniqueness strictly is what would let "Rolf" and
    /// "rolf" both exist, after which a login has two answers and picks one.
    /// </summary>
    public static IQueryable<User> WhereUsernameMatches(this IQueryable<User> users, string name) =>
        users.Where(u => EF.Functions.Collate(u.Username, "NOCASE") == name);
}
