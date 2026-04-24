import { useQuery } from '@tanstack/react-query';
import { Link, useParams } from 'react-router-dom';
import { api } from '../api/client';
import { BookCard } from '../components/BookCard';

export function SeriesDetailPage() {
  const { id } = useParams<{ id: string }>();
  const seriesId = Number(id);

  const { data: series, isLoading, isError, error } = useQuery({
    queryKey: ['series', seriesId],
    queryFn: () => api.getSeries(seriesId),
    enabled: Number.isFinite(seriesId)
  });

  if (isLoading) return <div className="p-6 text-ink-400">Lade...</div>;
  if (isError) return <div className="p-6 text-red-400">Fehler: {(error as Error).message}</div>;
  if (!series) return null;

  return (
    <div className="p-6 max-w-7xl mx-auto w-full">
      <div className="mb-6">
        <Link to="/series" className="text-ink-400 hover:text-ink-100 text-sm">
          ← Serien
        </Link>
        <h1 className="text-2xl font-semibold text-ink-100 mt-2">{series.name}</h1>
        <div className="text-ink-400 text-sm mt-1">
          {series.books.length} Band{series.books.length === 1 ? '' : 'e'}
        </div>
        {series.description && <p className="text-ink-300 mt-3">{series.description}</p>}
      </div>

      <div className="grid grid-cols-2 sm:grid-cols-3 md:grid-cols-4 lg:grid-cols-5 xl:grid-cols-6 gap-4">
        {series.books.map((book) => (
          <BookCard key={book.id} book={book} />
        ))}
      </div>
    </div>
  );
}
