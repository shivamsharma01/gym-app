import { z } from 'zod'

/** Shared create/edit member form rules. */
export const memberFormSchema = z.object({
  firstName: z.string().min(1, 'Required'),
  lastName: z.string().optional(),
  email: z.string().email('Invalid email').optional().or(z.literal('')),
  phone: z
    .string()
    .optional()
    .refine((v) => !v || /^\d{10}$/.test(v), 'Enter a 10-digit phone number'),
  dateOfBirth: z.string().optional(),
  gender: z.string(),
  notes: z.string().optional(),
  deviceAuthority: z.enum(['USER', 'ADMIN']).optional(),
  serialNumber: z
    .string()
    .optional()
    .refine((v) => !v || /^[A-Za-z0-9_-]{1,31}$/.test(v.trim()), "Use letters, digits, '-' or '_' (up to 31)"),
})

export type MemberFormValues = z.infer<typeof memberFormSchema>
