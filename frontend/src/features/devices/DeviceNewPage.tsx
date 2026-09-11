import { zodResolver } from '@hookform/resolvers/zod'
import { useMutation, useQuery } from '@tanstack/react-query'
import { useForm } from 'react-hook-form'
import { useNavigate } from 'react-router'
import { z } from 'zod'
import { Button, FieldError, Input, Label, PageHeader, Select } from '@/components/ui'
import { QueryError } from '@/components/QueryError'
import { api } from '@/lib/api'
import type { Device, Gateway, PageResponse } from '@/lib/types'

const schema = z.object({
  name: z.string().min(1, 'Required'),
  role: z.string(),
  host: z.string().optional(),
  port: z.string().optional(),
  model: z.string().optional(),
  serialNumber: z.string().optional(),
  gatewayId: z.string().optional(),
})

type Form = z.infer<typeof schema>

export function DeviceNewPage() {
  const navigate = useNavigate()
  const gateways = useQuery({
    queryKey: ['gateways'],
    queryFn: () => api<PageResponse<Gateway>>('/api/v1/gateways?size=50'),
  })
  const form = useForm<Form>({
    resolver: zodResolver(schema),
    defaultValues: { name: '', role: 'ENTRANCE', host: '', port: '37777', model: '', serialNumber: '', gatewayId: '' },
  })
  const create = useMutation({
    mutationFn: (body: Form) =>
      api<Device>('/api/v1/devices', {
        method: 'POST',
        body: JSON.stringify({
          name: body.name,
          role: body.role,
          host: body.host || null,
          port: body.port ? Number(body.port) : null,
          model: body.model || null,
          serialNumber: body.serialNumber || null,
          gatewayId: body.gatewayId || null,
        }),
      }),
    onSuccess: (d) => navigate(`/app/devices/${d.id}`),
  })

  return (
    <div className="max-w-xl">
      <PageHeader title="Register device" description="Device passwords stay on the LAN gateway, not in this form." />
      <form className="space-y-4" onSubmit={form.handleSubmit((v) => create.mutate(v))}>
        <div>
          <Label>Name</Label>
          <Input {...form.register('name')} />
          <FieldError message={form.formState.errors.name?.message} />
        </div>
        <div>
          <Label>Role</Label>
          <Select {...form.register('role')}>
            <option value="ENTRANCE">Entrance</option>
            <option value="EXIT">Exit</option>
            <option value="UNSPECIFIED">Unspecified</option>
          </Select>
        </div>
        <div className="grid gap-4 sm:grid-cols-2">
          <div>
            <Label>Host</Label>
            <Input {...form.register('host')} placeholder="192.168.1.50" />
          </div>
          <div>
            <Label>Port</Label>
            <Input type="number" {...form.register('port')} />
          </div>
        </div>
        <div>
          <Label>Model</Label>
          <Input {...form.register('model')} />
        </div>
        <div>
          <Label>Serial number</Label>
          <Input {...form.register('serialNumber')} />
        </div>
        <div>
          <Label>Gateway</Label>
          <Select {...form.register('gatewayId')}>
            <option value="">None yet</option>
            {gateways.data?.content.map((g) => (
              <option key={g.id} value={g.id}>
                {g.name}
              </option>
            ))}
          </Select>
        </div>
        {create.error ? <QueryError error={create.error} /> : null}
        <Button type="submit" disabled={create.isPending}>
          Create
        </Button>
      </form>
    </div>
  )
}
