using Fabula.Core.Domain;

namespace Fabula.Core.Services;

public interface IStreamingService
{
    Task<AudioFile?> GetAudioFileAsync(int audioFileId, CancellationToken cancellationToken);
}
