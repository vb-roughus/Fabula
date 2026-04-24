using Fabula.Core.Domain;
using Fabula.Core.Services;
using Microsoft.EntityFrameworkCore;

namespace Fabula.Data;

public class StreamingService(FabulaDbContext db) : IStreamingService
{
    public Task<AudioFile?> GetAudioFileAsync(int audioFileId, CancellationToken cancellationToken)
        => db.AudioFiles.FirstOrDefaultAsync(f => f.Id == audioFileId, cancellationToken);
}
