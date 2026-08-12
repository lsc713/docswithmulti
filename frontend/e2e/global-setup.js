import { randomUUID } from 'node:crypto'
import { request } from '@playwright/test'

import { setupRunCatalog } from './helpers/catalog-setup.js'
import { assertE2EAuthCookieCompatibility } from './helpers/urls.js'

export default async function globalSetup() {
  assertE2EAuthCookieCompatibility()
  const context = await request.newContext()
  try {
    await setupRunCatalog(context, { runKey: randomUUID() })
  } finally {
    await context.dispose()
  }
}
