const ORDER_STATUS = {
  PENDING: '결제 대기', PAYMENT_VERIFYING: '결제 확인 중', DELIVERY_WAITING: '배송 대기',
  CANCELLED: '취소 완료', PARTIAL_CANCELLED: '부분 취소',
}
const SETTLEMENT_STATUS = { OPEN: '집계 중', FINALIZED: '정산 확정' }

export const orderStatusLabel = status => ORDER_STATUS[status] ?? status
export const settlementStatusLabel = status => SETTLEMENT_STATUS[status] ?? status

export function buildSettlementPath(merchantId, status = '') {
  const params = new URLSearchParams({ merchantId: String(merchantId) })
  if (status) params.set('status', status)
  return `/v1/admin/settlements?${params}`
}
