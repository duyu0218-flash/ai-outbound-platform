import { useState } from 'react'
import { Button, Select, Space } from 'antd'
import { useQuery } from '@tanstack/react-query'
import { apiRequest } from './api'
import type { ScriptTemplate } from './types'

export function ScriptTemplateSelect({ token, value, onChange }: { token: string | null; value?: number; onChange?: (id?: number) => void }) {
  const [page, setPage] = useState(1)
  const query = useQuery({ queryKey: ['scripts', 'selector', page], enabled: Boolean(token),
    queryFn: () => apiRequest<ScriptTemplate[]>(`/api/v1/script-templates?active_only=true&page=${page}&size=50`, {}, token) })
  const selected = useQuery({ queryKey: ['scripts', 'selected', value], enabled: Boolean(token && value),
    queryFn: () => apiRequest<ScriptTemplate>(`/api/v1/script-templates/${value}`, {}, token) })
  const items = [...(query.data || [])]
  if (selected.data && !items.some((item) => item.id === selected.data.id)) items.unshift(selected.data)
  return <Select allowClear value={value} onChange={onChange} loading={query.isFetching}
    options={items.map((item) => ({ value: item.id, label: `${item.name} · v${item.version}` }))}
    dropdownRender={(menu) => <>{menu}<Space style={{ padding: 8 }} onMouseDown={(event) => event.preventDefault()}>
      <Button disabled={page === 1 || query.isFetching} onClick={() => setPage(page - 1)}>上一页话术</Button>
      <span>第 {page} 页</span><Button disabled={(query.data?.length || 0) < 50 || query.isFetching} onClick={() => setPage(page + 1)}>下一页话术</Button>
    </Space></>} />
}
