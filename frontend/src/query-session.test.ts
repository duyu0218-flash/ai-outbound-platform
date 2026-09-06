import { expect, it } from 'vitest'
import { createSessionQueryClient, disposeSessionQueries } from './query-session'

it('isolates the same query key across authenticated sessions', async () => {
  const first = createSessionQueryClient()
  const second = createSessionQueryClient()
  await first.fetchQuery({ queryKey: ['contacts'], queryFn: async () => ['tenant-A'] })
  expect(second.getQueryData(['contacts'])).toBeUndefined()
  expect(await second.fetchQuery({ queryKey: ['contacts'], queryFn: async () => ['tenant-B'] })).toEqual(['tenant-B'])
  disposeSessionQueries(first)
  expect(first.getQueryCache().getAll()).toHaveLength(0)
  expect(second.getQueryData(['contacts'])).toEqual(['tenant-B'])
  disposeSessionQueries(second)
})

it('prevents an in-flight response from repopulating a disposed session', async () => {
  const client = createSessionQueryClient()
  let finish!: (value: string[]) => void
  const pending = client.fetchQuery({ queryKey: ['calls'], queryFn: () => new Promise<string[]>((resolve) => { finish = resolve }) })
  const rejected = pending.catch(() => undefined)
  disposeSessionQueries(client)
  finish(['old-session'])
  await rejected
  expect(client.getQueryData(['calls'])).toBeUndefined()
  expect(client.getQueryCache().getAll()).toHaveLength(0)
})
