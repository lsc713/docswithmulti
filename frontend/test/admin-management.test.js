import assert from 'node:assert/strict'
import test from 'node:test'

import { buildSettlementPath, orderStatusLabel, settlementStatusLabel } from '../src/admin/management.js'

test('builds an encoded settlement query for the selected merchant and status', () => {
  assert.equal(buildSettlementPath(7, 'FINALIZED'), '/v1/admin/settlements?merchantId=7&status=FINALIZED')
  assert.equal(buildSettlementPath(7, ''), '/v1/admin/settlements?merchantId=7')
})

test('uses concise Korean labels for operational statuses', () => {
  assert.equal(orderStatusLabel('DELIVERY_WAITING'), '배송 대기')
  assert.equal(settlementStatusLabel('FINALIZED'), '정산 확정')
  assert.equal(orderStatusLabel('UNKNOWN'), 'UNKNOWN')
})
