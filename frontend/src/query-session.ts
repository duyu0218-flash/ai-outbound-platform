import { QueryClient } from '@tanstack/react-query'

// Each authenticated session owns a separate cache, including mutations.
// Identity isolation at the provider also covers all direct useQuery callers.
export function createSessionQueryClient() {
  return new QueryClient({
    defaultOptions: { queries: { staleTime: 10_000, retry: 1, refetchOnWindowFocus: false } },
  })
}

export function disposeSessionQueries(client: QueryClient) {
  void client.cancelQueries()
  client.clear()
}
