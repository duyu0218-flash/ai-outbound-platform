import { useEffect, useMemo, useState } from 'react'
import { useInfiniteQuery, useQuery } from '@tanstack/react-query'
import { Alert, Button, Select, Space, Typography } from 'antd'
import { useTranslation } from 'react-i18next'
import { apiRequest } from './api'
import { useAuth } from './auth'
import type { Contact } from './types'

const pageSize = 200

export function CampaignContactSelect({ value = [], onChange, id }: {
  value?: number[]; onChange?: (value: number[]) => void; id?: string
}) {
  const { token } = useAuth()
  const { t } = useTranslation()
  const [search, setSearch] = useState('')
  const [keyword, setKeyword] = useState('')
  useEffect(() => {
    const timer = window.setTimeout(() => setKeyword(search.trim()), 250)
    return () => window.clearTimeout(timer)
  }, [search])
  const contacts = useInfiniteQuery({
    queryKey: ['contacts', 'campaign-options', keyword],
    initialPageParam: 1,
    queryFn: ({ pageParam, signal }) => apiRequest<Contact[]>(
      `/api/v1/contacts?page=${pageParam}&size=${pageSize}&keyword=${encodeURIComponent(keyword)}`, { signal }, token,
    ),
    getNextPageParam: (last, pages) => last.length === pageSize ? pages.length + 1 : undefined,
    enabled: Boolean(token),
  })
  // Resolve saved selections independently of the search/page so editing and
  // switching search terms cannot discard IDs or replace labels with bare IDs.
  const selected = useQuery({
    queryKey: ['contacts', 'campaign-selected', value],
    queryFn: async ({ signal }) => {
      const rows: Contact[] = []
      for (let offset = 0; offset < value.length; offset += pageSize) {
        const params = new URLSearchParams({ size: String(pageSize) })
        value.slice(offset, offset + pageSize).forEach((contactId) => params.append('ids', String(contactId)))
        rows.push(...await apiRequest<Contact[]>(`/api/v1/contacts?${params}`, { signal }, token))
      }
      return rows
    },
    enabled: Boolean(token) && value.length > 0,
  })
  const options = useMemo(() => {
    const records = new Map<number, Contact>()
    for (const row of [...(selected.data || []), ...(contacts.data?.pages.flat() || [])]) records.set(row.id, row)
    return [...records.values()].map(row => ({ value: row.id, label: `${row.name || '-'} · ${row.phone}` }))
  }, [selected.data, contacts.data])
  return <Space direction="vertical" className="full-width">
    <Select id={id} className="full-width" mode="multiple" value={value} onChange={onChange}
      showSearch filterOption={false} onSearch={setSearch} options={options}
      loading={contacts.isFetching || selected.isFetching}
      maxTagCount="responsive"
      popupRender={menu => <>{menu}{contacts.hasNextPage && <Button block type="link"
        onMouseDown={event => event.preventDefault()} loading={contacts.isFetchingNextPage}
        onClick={() => void contacts.fetchNextPage()}>{t('loadMoreContacts')}</Button>}</>}
    />
    <Typography.Text type="secondary">{t('selectedContactsCount', { count: value.length })}</Typography.Text>
    {(contacts.isError || selected.isError) && <Alert type="error" showIcon
      message={t('loadFailed')} description={(contacts.error || selected.error)?.message}
      action={<Button onClick={() => { void contacts.refetch(); if (value.length) void selected.refetch() }}>{t('refresh')}</Button>} />}
  </Space>
}
