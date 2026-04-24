using Microsoft.EntityFrameworkCore;
using Microsoft.Extensions.DependencyInjection;

namespace Fabula.Data;

public static class DependencyInjection
{
    public static IServiceCollection AddFabulaData(this IServiceCollection services, string connectionString)
    {
        services.AddDbContext<FabulaDbContext>(options => options.UseSqlite(connectionString));
        return services;
    }
}
