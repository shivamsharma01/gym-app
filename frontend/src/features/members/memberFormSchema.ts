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
})

export type MemberFormValues = z.infer<typeof memberFormSchema>
