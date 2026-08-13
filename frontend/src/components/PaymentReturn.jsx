import { useEffect, useRef, useState } from 'react'
import { api } from '../api'

const messages = {
  PAY_PROCESS_CANCELED: '결제가 취소되었습니다.',
  USER_CANCEL: '결제가 취소되었습니다.',
}

export default function PaymentReturn({ kind, context, onCompleted, onRetry }) {
  const started = useRef(false)
  const completed = useRef(onCompleted)
  completed.current = onCompleted
  const [state, setState] = useState({ status: 'PENDING', message: '결제 결과를 확인하고 있습니다.' })

  useEffect(() => {
    if (started.current) return
    started.current = true
    let timer
    const params = new URLSearchParams(window.location.search)
    const requestId = params.get('orderId') || context?.paymentRequestId

    async function poll() {
      try {
        const payment = await api.getPaymentAttempt(requestId)
        if (payment.status === 'COMPLETED') return completed.current(payment)
        if (payment.status === 'FAILED') return setState({ status: 'FAILED', message: '결제를 완료하지 못했습니다.' })
        setState({ status: 'PENDING', message: '결제를 처리하고 있습니다. 잠시만 기다려 주세요.' })
        timer = setTimeout(poll, 2000)
      } catch (e) {
        setState({ status: 'FAILED', message: e.message })
      }
    }

    async function run() {
      if (!requestId || !context) return setState({ status: 'FAILED', message: '결제 시도 정보를 찾을 수 없습니다.' })
      if (kind === 'fail') {
        try {
          const payment = await api.failPayment(requestId)
          if (payment.status === 'COMPLETED') return completed.current(payment)
          if (payment.status === 'PENDING') return poll()
        } catch { return poll() }
        const code = params.get('code')
        return setState({ status: 'FAILED', message: messages[code] || params.get('message') || '결제를 완료하지 못했습니다.' })
      }
      try {
        const payment = await api.confirmPayment(requestId, {
          paymentKey: params.get('paymentKey'), orderId: params.get('orderId'),
          amount: Number(params.get('amount')),
        })
        if (payment.status === 'COMPLETED') return completed.current(payment)
      } catch { /* 승인 결과 불명은 상태 조회 */ }
      poll()
    }

    run()
    return () => clearTimeout(timer)
  }, [context, kind])

  return (
    <main className="checkout payment-result">
      <h1>{state.status === 'FAILED' ? '결제를 완료하지 못했습니다' : '결제 처리 중'}</h1>
      <p className={state.status === 'FAILED' ? 'error' : ''}>{state.message}</p>
      {state.status === 'PENDING' && <button onClick={() => window.location.reload()}>상태 다시 확인</button>}
      {state.status === 'FAILED' && context && <button className="pay-btn" onClick={() => onRetry(context)}>다시 결제</button>}
    </main>
  )
}
