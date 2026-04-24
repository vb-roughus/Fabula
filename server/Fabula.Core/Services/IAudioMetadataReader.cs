namespace Fabula.Core.Services;

public interface IAudioMetadataReader
{
    AudioMetadata Read(string filePath);
}
