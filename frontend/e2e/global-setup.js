import { randomUUID } from 'node:crypto'
import { request } from '@playwright/test'

import { setupRunCatalog } from './helpers/catalog-setup.js'

export default async function globalSetup() {
  const context = await request.newContext()
  try {
    await setupRunCatalog(context, { runKey: randomUUID() })
  } finally {
    await context.dispose()
  }
}
